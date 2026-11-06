// Unit test of app/src/main/cpp/profile_text.hpp, the .ovpn rewrites done
// before OpenVPN 3 reads a profile. No OpenVPN 3 needed:
//   cmake --build <build dir> --target profile_text_test && <build dir>/profile_text_test
#include <cstdio>
#include <string>

#include "profile_text.hpp"

static int failures = 0;

static void expect(bool ok, const char *what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

static std::string check(const std::string &profile, chain::ServerCertCheck &done) {
    return chain::require_server_cert_check(profile, done);
}

int main() {
    chain::ServerCertCheck done;

    // No check at all: one is added.
    std::string p = "client\nremote vpn.example.com 1194\n<ca>\nMIIB\n</ca>\n";
    expect(check(p, done) == p + "remote-cert-tls server\n", "adds remote-cert-tls server");
    expect(done.added && !done.ns_cert_type_rewritten, "reports the added check");

    // Profiles that already check the server are left as they are.
    for (const char *line : {"remote-cert-tls server", "verify-x509-name vpn.example.com name",
                             "remote-cert-eku \"TLS Web Server Authentication\"",
                             "  remote-cert-tls server"}) {
        std::string q = "client\n" + std::string(line) + "\nremote vpn.example.com 1194\n";
        expect(check(q, done) == q && !done.added, line);
    }
    // peer-fingerprint: OpenVPN 3 ignores it with mbed TLS, so it checks nothing here.
    for (const std::string &q : {std::string("client\npeer-fingerprint AB:CD\n"),
                                 std::string("client\n<peer-fingerprint>\nAB:CD:EF\n</peer-fingerprint>\n")}) {
        expect(check(q, done) == q + "remote-cert-tls server\n" && done.added, "peer-fingerprint doesn't count");
    }

    // ns-cert-type server becomes remote-cert-tls server; CRLF kept.
    expect(check("client\r\nns-cert-type server\r\n", done) == "client\r\nremote-cert-tls server\r\n",
           "rewrites ns-cert-type server");
    expect(done.ns_cert_type_rewritten && !done.added, "reports the rewrite only");

    // Not a check: comments, remote-cert-ku alone, text inside inline blocks.
    for (const std::string &q : {
             std::string("client\n# remote-cert-tls server\n"),
             std::string("client\n;verify-x509-name vpn.example.com\n"),
             std::string("client\nremote-cert-ku a0 88\n"),
             std::string("client\n<connection>\nremote a 1\n</connection>\n<cert>\nremote-cert-tls server\n</cert>\n"),
         }) {
        expect(check(q, done) == q + "remote-cert-tls server\n" && done.added, q.c_str());
    }

    // Lines after an inline block are read again.
    std::string after = "client\n<ca>\nMIIB\n</ca>\nremote-cert-tls server\n";
    expect(check(after, done) == after && !done.added, "options after an inline block");

    // tls-version-min 1.3 gets or-highest; anything else is left alone.
    bool changed = false;
    expect(chain::relax_tls_version_min("tls-version-min 1.3\n", changed) == "tls-version-min 1.3 or-highest\n" && changed,
           "relaxes tls-version-min 1.3");
    expect(chain::relax_tls_version_min("tls-version-min 1.2\n", changed) == "tls-version-min 1.2\n" && !changed,
           "keeps tls-version-min 1.2");

    if (failures == 0)
        std::puts("profile_text_test: OK");
    return failures == 0 ? 0 : 1;
}
