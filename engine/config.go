package engine

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// BuildXrayConfig turns a vless:// share link into a complete Xray JSON
// config: a "tun" inbound reading the Android VpnService fd, and the VLESS
// server as the only outbound.
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
	out, err := vlessOutbound(link)
	if err != nil {
		return "", err
	}
	cfg := map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"inbounds":  []any{inbound},
		"outbounds": []any{out},
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	return string(b), err
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
