package engine

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// BuildXrayConfig turns a vless://, socks:// or http(s):// link into a
// complete Xray JSON config: a "tun" inbound reading the Android VpnService
// fd, and that server as the only outbound for apps' traffic.
func BuildXrayConfig(link string, mtu int32) (string, error) {
	return buildConfig(link, map[string]any{
		"tag":      "tun-in",
		"protocol": "tun",
		"settings": map[string]any{"name": "xray0", "mtu": mtu},
		"sniffing": map[string]any{
			"enabled":      true,
			"destOverride": []string{"http", "tls", "quic"},
			"routeOnly":    true,
		},
	})
}

func buildConfig(link string, inbound map[string]any) (string, error) {
	out, err := proxyOutbound(link)
	if err != nil {
		return "", err
	}
	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"inbounds":  []any{inbound},
		"outbounds": []any{out},
	}
	if out["protocol"] != "vless" {
		addDNSOverHTTPS(cfg, inbound["tag"])
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	return string(b), err
}

// dohServer answers apps' DNS through the proxy.
const dohServer = "https://1.1.1.1/dns-query"

// addDNSOverHTTPS sends apps' DNS queries (UDP port 53, from inboundTag) to
// Xray's DNS, which asks dohServer over TCP through the proxy, the first
// outbound. An HTTP proxy can't carry UDP at all, and many SOCKS proxies
// (ssh -D, for one) don't either; without this no name would resolve.
// A and AAAA queries are answered; other types get an empty answer.
func addDNSOverHTTPS(cfg map[string]any, inboundTag any) {
	cfg["dns"] = map[string]any{"servers": []string{dohServer}}
	cfg["outbounds"] = append(cfg["outbounds"].([]any), map[string]any{
		"tag":      "dns-out",
		"protocol": "dns",
		"settings": map[string]any{"rules": []any{
			map[string]any{"action": "hijack", "qType": "1,28"},
			map[string]any{"action": "return", "rCode": 0},
		}},
	})
	rule := map[string]any{"type": "field", "network": "udp", "port": 53, "outboundTag": "dns-out"}
	if inboundTag != nil {
		rule["inboundTag"] = []any{inboundTag}
	}
	cfg["routing"] = map[string]any{"rules": []any{rule}}
}

// proxyOutbound picks the outbound for link by its scheme.
func proxyOutbound(link string) (map[string]any, error) {
	link = strings.TrimSpace(link)
	scheme, _, _ := strings.Cut(link, "://")
	switch strings.ToLower(scheme) {
	case "vless":
		return vlessOutbound(link)
	case "socks", "socks5", "socks5h", "http", "https":
		return socksOrHTTPOutbound(link)
	}
	return nil, errors.New(`link must start with "vless://", "socks://" or "http://"`)
}

// socksOrHTTPOutbound parses a SOCKS5 or HTTP proxy URL:
//
//	socks5://user:pass@host:1080#name
//	http://user:pass@host:8080
//	https://host:443?sni=proxy.example.com   (HTTP proxy spoken over TLS)
//
// v2rayN and v2rayNG share these with the credentials base64-encoded:
// socks://dXNlcjpwYXNz@host:1080. Some proxy sellers put the credentials
// last: http://host:8080@user:pass. All three forms are accepted.
func socksOrHTTPOutbound(link string) (map[string]any, error) {
	u, err := url.Parse(standardProxyLink(link))
	if err != nil {
		return nil, fmt.Errorf("bad proxy link: %w", err)
	}
	scheme := strings.ToLower(u.Scheme)
	protocol := "http"
	if strings.HasPrefix(scheme, "socks") {
		protocol = "socks"
	}
	host := u.Hostname()
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if host == "" || err != nil || port == 0 {
		return nil, fmt.Errorf("proxy link must look like %s://host:port", scheme)
	}

	settings := map[string]any{"address": host, "port": port}
	if u.User != nil {
		user := u.User.Username()
		pass, hasPass := u.User.Password()
		if !hasPass {
			if decoded, ok := base64Credentials(user); ok {
				user, pass, _ = strings.Cut(decoded, ":")
			}
		}
		if user != "" || pass != "" {
			settings["user"], settings["pass"] = user, pass
		}
	}

	out := map[string]any{"tag": "proxy", "protocol": protocol, "settings": settings}
	if scheme == "https" {
		q := u.Query()
		tls := map[string]any{"serverName": host}
		if sni := q.Get("sni"); sni != "" {
			tls["serverName"] = sni
		}
		if fp := q.Get("fp"); fp != "" {
			tls["fingerprint"] = fp
		}
		out["streamSettings"] = map[string]any{"network": "tcp", "security": "tls", "tlsSettings": tls}
	}
	return out, nil
}

// standardProxyLink rewrites "scheme://host:port@user:pass" to the standard
// "scheme://user:pass@host:port". A link already in the standard form is
// returned unchanged, so is one that is neither.
//
// "a:1@b:2" reads both ways. It is taken as reversed only when "a" looks
// like a server (an IP address, or a name with a dot, or localhost) and "b"
// doesn't, as in "1.2.3.4:8080@user:1234".
func standardProxyLink(link string) string {
	scheme, rest, ok := strings.Cut(link, "://")
	if !ok {
		return link
	}
	end := strings.IndexAny(rest, "/?#")
	if end < 0 {
		end = len(rest)
	}
	authority, tail := rest[:end], rest[end:]
	server, credentials, ok := strings.Cut(authority, "@")
	if !ok || !looksLikeServer(server) {
		return link
	}
	standardServer := authority[strings.LastIndex(authority, "@")+1:]
	if looksLikeServer(standardServer) {
		return link
	}
	return scheme + "://" + credentials + "@" + server + tail
}

// looksLikeServer reports whether hostPort is "host:port" with a valid port
// and a host that is an IP address, a name with a dot, or localhost.
func looksLikeServer(hostPort string) bool {
	host, portText, err := net.SplitHostPort(hostPort)
	if err != nil {
		return false
	}
	if port, err := strconv.ParseUint(portText, 10, 16); err != nil || port == 0 {
		return false
	}
	return net.ParseIP(host) != nil || strings.Contains(host, ".") || strings.EqualFold(host, "localhost")
}

// base64Credentials decodes v2rayN's base64 "user:pass" userinfo.
func base64Credentials(s string) (string, bool) {
	s = strings.TrimRight(s, "=")
	for _, enc := range []*base64.Encoding{base64.RawStdEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(s); err == nil && strings.Contains(string(b), ":") {
			return string(b), true
		}
	}
	return "", false
}

// vlessOutbound parses the de-facto standard VLESS share link format
// (https://github.com/XTLS/Xray-core/discussions/716):
//
//	vless://uuid@host:port?type=tcp&security=reality&sni=...&pbk=...&sid=...&fp=chrome&flow=xtls-rprx-vision#name
func vlessOutbound(link string) (map[string]any, error) {
	u, err := url.Parse(strings.TrimSpace(link))
	if err != nil {
		return nil, fmt.Errorf("bad VLESS link: %w", err)
	}
	if u.Scheme != "vless" {
		return nil, errors.New(`VLESS link must start with "vless://"`)
	}
	id := u.User.Username()
	host := u.Hostname()
	port, err := strconv.ParseUint(u.Port(), 10, 16)
	if id == "" || host == "" || err != nil || port == 0 {
		return nil, errors.New("VLESS link must look like vless://uuid@host:port?...")
	}
	q := u.Query()
	get := func(key, def string) string {
		if v := q.Get(key); v != "" {
			return v
		}
		return def
	}

	user := map[string]any{"id": id, "encryption": get("encryption", "none")}
	if flow := q.Get("flow"); flow != "" {
		user["flow"] = flow
	}

	network := get("type", "tcp")
	security := get("security", "none")
	stream := map[string]any{"network": network, "security": security}

	path, hostHeader := q.Get("path"), q.Get("host")
	switch network {
	case "tcp", "raw":
		if q.Get("headerType") == "http" {
			req := map[string]any{"path": []string{get("path", "/")}}
			if hostHeader != "" {
				req["headers"] = map[string]any{"Host": strings.Split(hostHeader, ",")}
			}
			stream["tcpSettings"] = map[string]any{"header": map[string]any{"type": "http", "request": req}}
		}
	case "ws":
		stream["wsSettings"] = map[string]any{"path": path, "host": hostHeader}
	case "httpupgrade":
		stream["httpupgradeSettings"] = map[string]any{"path": path, "host": hostHeader}
	case "xhttp", "splithttp":
		stream["network"] = "xhttp"
		stream["xhttpSettings"] = map[string]any{"path": path, "host": hostHeader, "mode": get("mode", "auto")}
	case "grpc":
		stream["grpcSettings"] = map[string]any{
			"serviceName": q.Get("serviceName"),
			"authority":   q.Get("authority"),
			"multiMode":   q.Get("mode") == "multi",
		}
	default:
		return nil, fmt.Errorf("unsupported VLESS transport %q", network)
	}

	sni := get("sni", hostHeader)
	switch security {
	case "none", "":
	case "tls":
		tls := map[string]any{"serverName": sni, "fingerprint": q.Get("fp")}
		if alpn := q.Get("alpn"); alpn != "" {
			tls["alpn"] = strings.Split(alpn, ",")
		}
		if pcs := q.Get("pcs"); pcs != "" {
			tls["pinnedPeerCertSha256"] = pcs
		}
		if vcn := q.Get("vcn"); vcn != "" {
			tls["verifyPeerCertByName"] = vcn
		}
		if ech := q.Get("ech"); ech != "" {
			tls["echConfigList"] = ech
		}
		stream["tlsSettings"] = tls
	case "reality":
		stream["realitySettings"] = map[string]any{
			"serverName":    sni,
			"fingerprint":   get("fp", "chrome"),
			"publicKey":     q.Get("pbk"),
			"shortId":       q.Get("sid"),
			"spiderX":       q.Get("spx"),
			"mldsa65Verify": q.Get("pqv"),
		}
	default:
		return nil, fmt.Errorf("unsupported VLESS security %q", security)
	}

	return map[string]any{
		"tag":      "proxy",
		"protocol": "vless",
		"settings": map[string]any{
			"vnext": []any{map[string]any{"address": host, "port": port, "users": []any{user}}},
		},
		"streamSettings": stream,
	}, nil
}
