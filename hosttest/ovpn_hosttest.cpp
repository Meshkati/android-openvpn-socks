// Runs chain::Client against a real OpenVPN server and, when the tunnel
// comes up, a child process with OVPN_ADDRS / OVPN_DNS / OVPN_MTU in its
// environment. The session is stopped when the child exits, and its exit
// code returned.
//
//   ovpn_hosttest [--device-tun] <profile.ovpn> <user> <pass> -- <command> [args...]
//
// Mode::Chain (default): the app end of the packet socketpair is passed to
// the child as fd 3 (OVPN_FD).
// Mode::Device (--device-tun): OpenVPN 3 gets a Linux TUN, set up with the
// pushed addresses and routes as the app does on Android. Its name is in
// OVPN_IFACE, the routes in OVPN_ROUTES. Needs root.
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <thread>

#include "chain_client.hpp"

namespace {

std::string join(const std::vector<std::string> &v) {
    std::string out;
    for (const auto &s : v)
        out += (out.empty() ? "" : ",") + s;
    return out;
}

class ForkListener : public chain::Listener {
public:
    explicit ForkListener(char **cmd) : cmd_(cmd) {}

    void set_client(chain::Client *c) { client_ = c; }
    int exit_code() const { return exit_code_; }
    void join_waiter() {
        if (waiter_.joinable())
            waiter_.join();
    }

    void on_log(const std::string &line) override { std::cerr << "[ovpn3] " << line << std::flush; }

    void on_event(const openvpn::ClientAPI::Event &ev) override {
        std::cerr << "[event] " << ev.name << " " << ev.info << (ev.fatal ? " (fatal)" : "") << "\n";
    }

    bool on_tun(int fd, const chain::TunConfig &cfg) override {
        if (started_.exchange(true)) {
            std::cerr << "[hosttest] tun re-established; not supported here\n";
            close(fd);
            return false;
        }
        std::cerr << "[hosttest] tun up: addrs=" << join(cfg.addresses) << " dns=" << join(cfg.dns)
                  << " mtu=" << cfg.mtu << "\n";
        const bool ok = start_child(cfg, fd, "");
        close(fd);
        return ok;
    }

    int on_device_tun(const chain::TunConfig &cfg) override {
        if (started_.exchange(true)) {
            std::cerr << "[hosttest] tun re-established; not supported here\n";
            return -1;
        }
        std::cerr << "[hosttest] device tun: addrs=" << join(cfg.addresses)
                  << " routes=" << join(cfg.routes) << " dns=" << join(cfg.dns)
                  << " domains=" << join(cfg.search_domains) << " mtu=" << cfg.mtu << "\n";
        const int fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            perror("open /dev/net/tun");
            return -1;
        }
        ifreq ifr{};
        ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
        std::strncpy(ifr.ifr_name, kIface, IFNAMSIZ - 1);
        bool ok = ioctl(fd, TUNSETIFF, &ifr) == 0;
        if (!ok)
            perror("TUNSETIFF");
        // Like VpnService.Builder: addresses, MTU, then routes via the TUN.
        for (const auto &a : cfg.addresses)
            ok = ok && ip("addr add " + a + " dev " + kIface);
        ok = ok && ip("link set " + std::string(kIface) + " mtu " + std::to_string(cfg.mtu) + " up");
        for (const auto &r : cfg.routes)
            ok = ok && ip("route replace " + r + " dev " + kIface);
        ok = ok && start_child(cfg, -1, kIface);
        if (!ok) {
            close(fd);
            return -1;
        }
        return fd;
    }

private:
    static constexpr const char *kIface = "ovpnhost";

    static bool ip(const std::string &args) {
        const std::string cmd = "ip " + args;
        if (std::system(cmd.c_str()) == 0)
            return true;
        std::cerr << "[hosttest] failed: " << cmd << "\n";
        return false;
    }

    // Runs the command with the tunnel settings in its environment and, if
    // packet_fd >= 0, that fd as fd 3. Stops the session when it exits.
    bool start_child(const chain::TunConfig &cfg, int packet_fd, const std::string &iface) {
        pid_t pid = fork();
        if (pid == 0) {
            if (packet_fd >= 0) {
                dup2(packet_fd, 3);  // dup2 clears FD_CLOEXEC on the copy
                setenv("OVPN_FD", "3", 1);
            }
            setenv("OVPN_IFACE", iface.c_str(), 1);
            setenv("OVPN_ADDRS", join(cfg.addresses).c_str(), 1);
            setenv("OVPN_ROUTES", join(cfg.routes).c_str(), 1);
            setenv("OVPN_DNS", join(cfg.dns).c_str(), 1);
            setenv("OVPN_MTU", std::to_string(cfg.mtu).c_str(), 1);
            execvp(cmd_[0], cmd_);
            perror("execvp");
            _exit(127);
        }
        if (pid < 0)
            return false;
        waiter_ = std::thread([this, pid] {
            int status = 0;
            waitpid(pid, &status, 0);
            exit_code_ = WIFEXITED(status) ? WEXITSTATUS(status) : 128;
            std::cerr << "[hosttest] child exited with " << exit_code_ << "\n";
            client_->stop();
        });
        return true;
    }

    char **cmd_;
    chain::Client *client_ = nullptr;
    std::atomic<bool> started_{false};
    std::atomic<int> exit_code_{1};
    std::thread waiter_;
};

}  // namespace

int main(int argc, char **argv) {
    auto mode = chain::Mode::Chain;
    if (argc > 1 && std::strcmp(argv[1], "--device-tun") == 0) {
        mode = chain::Mode::Device;
        argv++;
        argc--;
    }
    if (argc < 6 || std::strcmp(argv[4], "--") != 0) {
        std::cerr << "usage: " << argv[0]
                  << " [--device-tun] <profile.ovpn> <user> <pass> -- <command> [args...]\n";
        return 2;
    }
    std::ifstream in(argv[1]);
    std::stringstream profile;
    profile << in.rdbuf();

    openvpn::ClientAPI::OpenVPNClientHelper helper;  // process-wide init
    ForkListener listener(argv + 5);
    chain::Client client(listener);
    listener.set_client(&client);

    const std::string err = client.run(profile.str(), argv[2], argv[3], {}, mode);
    listener.join_waiter();
    if (!err.empty()) {
        std::cerr << "[hosttest] session error: " << err << "\n";
    }
    return listener.exit_code();
}
