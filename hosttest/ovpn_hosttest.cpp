// Runs chain::Client against a real OpenVPN server. When the tunnel comes
// up, the app end of the packet socketpair is passed to a child process as
// fd 3, together with OVPN_ADDRS / OVPN_DNS / OVPN_MTU in its environment.
// The session is stopped when the child exits, and its exit code returned.
//
//   ovpn_hosttest <profile.ovpn> <user> <pass> -- <command> [args...]
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
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
        pid_t pid = fork();
        if (pid == 0) {
            dup2(fd, 3);  // dup2 clears FD_CLOEXEC on the copy
            setenv("OVPN_FD", "3", 1);
            setenv("OVPN_ADDRS", join(cfg.addresses).c_str(), 1);
            setenv("OVPN_DNS", join(cfg.dns).c_str(), 1);
            setenv("OVPN_MTU", std::to_string(cfg.mtu).c_str(), 1);
            execvp(cmd_[0], cmd_);
            perror("execvp");
            _exit(127);
        }
        close(fd);
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

private:
    char **cmd_;
    chain::Client *client_ = nullptr;
    std::atomic<bool> started_{false};
    std::atomic<int> exit_code_{1};
    std::thread waiter_;
};

}  // namespace

int main(int argc, char **argv) {
    if (argc < 6 || std::strcmp(argv[4], "--") != 0) {
        std::cerr << "usage: " << argv[0] << " <profile.ovpn> <user> <pass> -- <command> [args...]\n";
        return 2;
    }
    std::ifstream in(argv[1]);
    std::stringstream profile;
    profile << in.rdbuf();

    openvpn::ClientAPI::OpenVPNClientHelper helper;  // process-wide init
    ForkListener listener(argv + 5);
    chain::Client client(listener);
    listener.set_client(&client);

    const std::string err = client.run(profile.str(), argv[2], argv[3]);
    listener.join_waiter();
    if (!err.empty()) {
        std::cerr << "[hosttest] session error: " << err << "\n";
    }
    return listener.exit_code();
}
