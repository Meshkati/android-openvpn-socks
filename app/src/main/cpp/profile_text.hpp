// Rewrites of the .ovpn text before OpenVPN 3 reads it. Plain C++ with no
// OpenVPN headers, so hosttest/profile_text_test.cpp can test it on its own.
#pragma once

#include <sstream>
#include <string>

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

// What require_server_cert_check() changed, for the log.
struct ServerCertCheck {
    bool ns_cert_type_rewritten = false;  // "ns-cert-type server" became "remote-cert-tls server"
    bool added = false;                   // the profile had no check; "remote-cert-tls server" added
};

// OpenVPN 3 checks only that the server's certificate comes from the
// profile's CA, unless the profile asks for more. On a server where users
// get client certificates from the same CA, any of them could then pose as
// the server. So a profile that sets none of the usual checks
// (remote-cert-tls, remote-cert-eku, verify-x509-name, peer-fingerprint) gets
// "remote-cert-tls server": the certificate must be marked for TLS server
// use (extended key usage serverAuth), as Easy-RSA and Access Server make
// them. A PKI whose server certificate isn't can use verify-x509-name.
//
// "ns-cert-type server", the old form of that check, is rewritten to
// "remote-cert-tls server": OpenVPN 3 with mbed TLS 3 can't check it, and
// refuses the profile (3.11.8) or failed every handshake (before).
//
// remote-cert-ku alone doesn't count: client certificates usually have the
// same key usage bits as the server's.
inline std::string require_server_cert_check(const std::string &profile, ServerCertCheck &done) {
    done = {};
    std::string out;
    std::istringstream in(profile);
    std::string line;
    std::string block;  // the inline block (<ca>, <cert>, ...) we're in, if any
    bool has_check = false;
    while (std::getline(in, line)) {
        const bool cr = !line.empty() && line.back() == '\r';
        if (cr)
            line.pop_back();
        std::istringstream words(line);
        std::string directive, arg;
        words >> directive >> arg;
        if (!block.empty()) {
            // Certificates and keys: their text is not options. A
            // <connection> block's is, but these checks aren't among them.
            if (directive == "</" + block + ">")
                block.clear();
        } else if (directive.size() > 2 && directive.front() == '<' && directive.back() == '>' &&
                   directive[1] != '/') {
            block = directive.substr(1, directive.size() - 2);
            if (block == "peer-fingerprint")
                has_check = true;
        } else if (directive == "ns-cert-type" && arg == "server") {
            line = "remote-cert-tls server";
            done.ns_cert_type_rewritten = true;
            has_check = true;
        } else if (directive == "remote-cert-tls" || directive == "remote-cert-eku" ||
                   directive == "verify-x509-name" || directive == "peer-fingerprint") {
            has_check = true;
        }
        out += line;
        out += cr ? "\r\n" : "\n";
    }
    if (!has_check) {
        out += "remote-cert-tls server\n";
        done.added = true;
    }
    return out;
}

}  // namespace chain
