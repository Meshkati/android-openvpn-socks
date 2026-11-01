// OpenVPN 3 client whose "tun" is either a socketpair or the device TUN.
//
// OpenVPN 3 core on Android talks to the OS through TunBuilderBase: it reports
// addresses/routes/DNS and finally asks tun_builder_establish() for a file
// descriptor carrying raw IP packets. Two modes:
//
//   Mode::Chain   We hand it one end of an AF_UNIX datagram socketpair and
//                 give the other end, plus the pushed settings, to the
//                 Listener. The Go engine attaches a userspace TCP/IP stack to
//                 that end, so traffic it sends lands inside the OpenVPN
//                 tunnel without the device routing table ever pointing at
//                 OpenVPN.
//   Mode::Device  OpenVPN only, no VLESS: the Listener builds the device TUN
//                 from the pushed settings (addresses, routes, DNS) and
//                 OpenVPN 3 reads and writes it directly, like any OpenVPN app.
//
// No JNI in here, so the same class runs in the host test (hosttest/).
#pragma once

#include <sys/socket.h>
#include <unistd.h>

#include <sstream>
#include <string>
#include <vector>

#include <ovpncli.hpp>

namespace chain {

// OpenVPN 3's mbed TLS backend caps tls-version-min at 1.2 when parsing, so it
// rejects "tls-version-min 1.3" before connecting ("unrecognized TLS
// version"), although mbed TLS itself negotiates TLS 1.3. OpenVPN Access
// Server writes that line into its profiles, so append "or-highest", which
// lowers only the minimum to 1.2; servers offering 1.3 still get 1.3. Sets
// `changed` if a line was rewritten.
inline std::string relax_tls_version_min(const std::string &profile, bool &changed) {
    std::string out;
    std::istringstream in(profile);
    std::string line;
    changed = false;
    while (std::getline(in, line)) {
        const bool cr = !line.empty() && line.back() == '\r';
        if (cr)
            line.pop_back();
        std::istringstream words(line);
        std::string directive, version, extra;
        words >> directive >> version >> extra;
        if (directive == "tls-version-min" && version == "1.3" && extra.empty()) {
            line += " or-highest";
            changed = true;
        }
        out += line;
        out += cr ? "\r\n" : "\n";
    }
    return out;
}

struct TunConfig {
    std::vector<std::string> addresses;       // CIDR, e.g. "10.8.0.6/24"
    // CIDR, IPv4 and IPv6. redirect-gateway shows up as 0.0.0.0/0 or ::/0, or,
    // on Android where the core emulates excluded routes, as the pieces of
    // the address space left after cutting them out.
    std::vector<std::string> routes;
    std::vector<std::string> dns;             // pushed DNS server addresses
    std::vector<std::string> search_domains;  // pushed DNS search domains
    int mtu = 1500;
};

enum class Mode { Chain, Device };

class Listener {
public:
    virtual ~Listener() = default;
    virtual void on_log(const std::string &line) = 0;
    virtual void on_event(const openvpn::ClientAPI::Event &ev) = 0;
    // Mode::Chain, called from tun_builder_establish(). fd is the app's end of
    // the packet socketpair and belongs to the listener from now on.
    // Returning false fails the connection.
    virtual bool on_tun(int fd, const TunConfig &cfg) = 0;
    // Mode::Device, called from tun_builder_establish(). Returns a TUN file
    // descriptor configured as `cfg` says, which OpenVPN 3 owns from then on,
    // or -1 to fail the connection.
    virtual int on_device_tun(const TunConfig &cfg) = 0;
};

class Client : public openvpn::ClientAPI::OpenVPNClient {
public:
    explicit Client(Listener &listener) : listener_(listener) {}

    // --- TunBuilderBase: record what the server pushed ---------------------

    bool tun_builder_new() override {
        cfg_ = TunConfig{};
        return true;
    }

    bool tun_builder_add_address(const std::string &address, int prefix_length,
                                 const std::string &, bool, bool) override {
        cfg_.addresses.push_back(address + "/" + std::to_string(prefix_length));
        return true;
    }

    bool tun_builder_set_dns_options(const openvpn::DnsOptions &dns) override {
        for (const auto &[priority, server] : dns.servers)
            for (const auto &addr : server.addresses)
                cfg_.dns.push_back(addr.address);
        for (const auto &domain : dns.search_domains)
            cfg_.search_domains.push_back(domain.domain);
        return true;
    }

    bool tun_builder_set_mtu(int mtu) override {
        if (mtu > 0)
            cfg_.mtu = mtu;
        return true;
    }

    // Routes only matter for the device TUN (Mode::Device); in Mode::Chain the
    // userspace stack sends everything it dials into the tunnel.
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        if (ipv4)
            cfg_.routes.push_back("0.0.0.0/0");
        if (ipv6)
            cfg_.routes.push_back("::/0");
        return true;
    }

    bool tun_builder_add_route(const std::string &address, int prefix_length, int, bool) override {
        cfg_.routes.push_back(address + "/" + std::to_string(prefix_length));
        return true;
    }

    // Not called on Android: the core emulates excluded routes there by
    // splitting the included ones (Android before 13 can't exclude routes).
    bool tun_builder_exclude_route(const std::string &address, int prefix_length, int, bool) override {
        listener_.on_log("ignoring excluded route " + address + "/" + std::to_string(prefix_length));
        return true;
    }

    bool tun_builder_set_remote_address(const std::string &, bool) override { return true; }
    bool tun_builder_set_session_name(const std::string &) override { return true; }
    bool tun_builder_add_proxy_bypass(const std::string &) override { return true; }
    bool tun_builder_set_proxy_auto_config_url(const std::string &) override { return true; }
    bool tun_builder_set_proxy_http(const std::string &, int) override { return true; }
    bool tun_builder_set_proxy_https(const std::string &, int) override { return true; }
    bool tun_builder_add_wins_server(const std::string &) override { return true; }

    int tun_builder_establish() override {
        if (mode_ == Mode::Device)
            return listener_.on_device_tun(cfg_);
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, sv) != 0) {
            listener_.on_log("socketpair failed");
            return -1;
        }
        const int buf = 1 << 20;
        for (int fd : sv) {
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, sizeof(buf));
            setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof(buf));
        }
        if (!listener_.on_tun(sv[1], cfg_)) {
            close(sv[0]);
            return -1;
        }
        return sv[0];  // owned by OpenVPN 3 core from here on
    }

    // --- OpenVPNClient -----------------------------------------------------

    // The app excludes itself from its own VpnService, so the OpenVPN
    // transport socket never loops back into the device TUN.
    bool socket_protect(openvpn_io::detail::socket_type, std::string, bool) override { return true; }

    bool pause_on_connection_timeout() override { return false; }

    void event(const openvpn::ClientAPI::Event &ev) override { listener_.on_event(ev); }

    void log(const openvpn::ClientAPI::LogInfo &info) override { listener_.on_log(info.text); }

    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent &) override {}

    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest &req) override {
        req.error = true;
        req.errorText = "external PKI is not supported";
    }

    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest &req) override {
        req.error = true;
        req.errorText = "external PKI is not supported";
    }

    // Parses the profile, supplies credentials if it needs them and runs the
    // session until it ends or stop() is called. `response` answers the
    // profile's static-challenge (e.g. an authenticator code). Returns "" on a
    // clean exit, otherwise an error message.
    std::string run(const std::string &profile, const std::string &username,
                    const std::string &password, const std::string &response = {},
                    Mode mode = Mode::Chain) {
        mode_ = mode;
        openvpn::ClientAPI::Config config;
        bool relaxed = false;
        config.content = relax_tls_version_min(profile, relaxed);
        if (relaxed)
            listener_.on_log("profile has tls-version-min 1.3, which OpenVPN 3 with mbed TLS "
                             "rejects; using tls-version-min 1.3 or-highest (minimum TLS 1.2)");
        config.guiVersion = "ovpn-vless-chain 0.1";
        config.tunPersist = true;  // keep the socketpair across reconnects
        config.dco = false;
        // Mode::Device: a profile that redirects all traffic but pushes no DNS
        // server gets 8.8.8.8 and 8.8.4.4, so apps' lookups don't go to a LAN
        // resolver that is now behind the tunnel.
        config.googleDnsFallback = mode == Mode::Device;
        // Pushed DOMAIN options are search domains, as on Android (the
        // default elsewhere is split DNS, which VpnService can't do).
        config.dhcpSearchDomainsAsSplitDomains = false;
        auto eval = eval_config(config);
        if (!eval.error && eval.externalPki) {
            // OpenVPN 3 reads a profile without <cert>/<key> as "external
            // PKI" unless it says "setenv CLIENT_CERT 0". External PKI is not
            // supported here, so treat it as a username/password-only profile,
            // which is what such profiles from OpenVPN 2.x servers mean.
            config.content += "\nclient-cert-not-required\n";
            eval = eval_config(config);
        }
        if (eval.error)
            return "profile error: " + eval.message;
        if (!eval.autologin) {
            if (!eval.staticChallenge.empty() && response.empty())
                return "the profile asks for \"" + eval.staticChallenge + "\"; enter the one-time code";
            openvpn::ClientAPI::ProvideCreds creds;
            creds.username = username;
            creds.password = password;
            // The core sends it as SCRV1:<base64 password>:<base64 response>.
            creds.response = response;
            const auto st = provide_creds(creds);
            if (st.error)
                return "credentials error: " + st.message;
        }
        const auto st = connect();
        if (st.error)
            return st.status.empty() ? st.message : st.status + ": " + st.message;
        return "";
    }

private:
    Listener &listener_;
    TunConfig cfg_;
    Mode mode_ = Mode::Chain;
};

}  // namespace chain
