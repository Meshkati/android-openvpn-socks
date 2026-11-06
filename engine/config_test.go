package engine

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/xtls/xray-core/infra/conf/serial"
)

func TestBuildXrayConfigAcceptedByXray(t *testing.T) {
	links := []string{
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@example.com:443?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp&flow=xtls-rprx-vision#reality",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@10.8.0.1:8443?type=ws&security=tls&sni=vless.example.com&path=%2Fws%3Fed%3D2048&host=vless.example.com&alpn=h2,http/1.1#ws",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@[fd00::1]:443?type=grpc&security=tls&serviceName=grpc&mode=multi",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@vless.internal:80?type=xhttp&path=/x&mode=packet-up",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@vless.internal:80?type=httpupgrade&path=/u&host=h",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@10.8.0.1:80?type=tcp&headerType=http&path=/&host=a.com,b.com",
		"vless://a3482e88-686a-4a58-8126-99c9df64b7bf@10.8.0.1:80",
		"socks5://user:p%40ss@10.8.0.1:1080#office",
		"socks://dXNlcjpwYXNz@proxy.internal:1080#v2rayN",
		"socks5h://[fd00::1]:1080",
		"http://user:pass@10.8.0.1:3128",
		"http://proxy.internal:8080",
		"https://user:pass@proxy.example.com:443?sni=front.example.com&fp=chrome",
	}
	for _, link := range links {
		cfg, err := BuildXrayConfig(link, 1400)
		if err != nil {
			t.Fatalf("%s: %v", link, err)
		}
		if _, err := serial.LoadJSONConfig(strings.NewReader(cfg)); err != nil {
			t.Fatalf("xray rejected config for %s: %v\n%s", link, err, cfg)
		}
	}
}

func TestVlessOutboundFields(t *testing.T) {
	ob, err := vlessOutbound("vless://uuid-1@[fd00::1]:2053?security=reality&sni=s.com&pbk=key&sid=ab&flow=xtls-rprx-vision")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(ob)
	for _, want := range []string{`"address":"fd00::1"`, `"port":2053`, `"id":"uuid-1"`, `"flow":"xtls-rprx-vision"`,
		`"encryption":"none"`, `"publicKey":"key"`, `"shortId":"ab"`, `"serverName":"s.com"`, `"fingerprint":"chrome"`} {
		if !strings.Contains(string(b), want) {
			t.Errorf("missing %s in %s", want, b)
		}
	}
}

func TestSocksOutboundFields(t *testing.T) {
	for link, want := range map[string][]string{
		"socks5://user:p%40ss@10.8.0.1:1080": {`"protocol":"socks"`, `"address":"10.8.0.1"`, `"port":1080`, `"user":"user"`, `"pass":"p@ss"`},
		// v2rayN/v2rayNG share links: base64 "user:pass".
		"socks://dXNlcjpwYXNz@h.example:1080":     {`"protocol":"socks"`, `"user":"user"`, `"pass":"pass"`},
		"SOCKS5H://[fd00::1]:1080":                {`"protocol":"socks"`, `"address":"fd00::1"`},
		"http://u:@h.example:3128":                {`"protocol":"http"`, `"user":"u"`, `"pass":""`},
		"https://h.example:443?fp=chrome":         {`"protocol":"http"`, `"security":"tls"`, `"serverName":"h.example"`, `"fingerprint":"chrome"`},
		"https://h.example:443?sni=front.example": {`"serverName":"front.example"`},
	} {
		ob, err := proxyOutbound(link)
		if err != nil {
			t.Fatalf("%s: %v", link, err)
		}
		b, _ := json.Marshal(ob)
		for _, w := range want {
			if !strings.Contains(string(b), w) {
				t.Errorf("%s: missing %s in %s", link, w, b)
			}
		}
	}
	for _, link := range []string{"socks5://h.example:1080", "http://h.example:8080"} {
		ob, _ := proxyOutbound(link)
		if b, _ := json.Marshal(ob); strings.Contains(string(b), `"user"`) || strings.Contains(string(b), "streamSettings") {
			t.Errorf("%s: unexpected credentials or TLS in %s", link, b)
		}
	}
}

func TestReversedProxyCredentials(t *testing.T) {
	for link, want := range map[string]string{
		// Credentials last, as some proxy sellers write them.
		"http://1.2.3.4:8080@user:pass":          "http://user:pass@1.2.3.4:8080",
		"socks5://proxy.example.com:1080@u:1234": "socks5://u:1234@proxy.example.com:1080",
		"http://[fd00::1]:3128@u:p@ss#name":      "http://u:p@ss@[fd00::1]:3128#name",
		"http://localhost:8080@u:p?x=1":          "http://u:p@localhost:8080?x=1",
		// Already standard, or neither: unchanged.
		"http://user:pass@1.2.3.4:8080":    "http://user:pass@1.2.3.4:8080",
		"http://admin:1234@squid:3128":     "http://admin:1234@squid:3128",
		"http://a.example:1@b.example:2":   "http://a.example:1@b.example:2",
		"http://1.2.3.4:8080":              "http://1.2.3.4:8080",
		"socks://dXNlcjpwYXNz@h.example:1": "socks://dXNlcjpwYXNz@h.example:1",
	} {
		if got := standardProxyLink(link); got != want {
			t.Errorf("standardProxyLink(%q) = %q, want %q", link, got, want)
		}
	}

	ob, err := proxyOutbound("http://1.2.3.4:8080@user:pass")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(ob)
	for _, w := range []string{`"address":"1.2.3.4"`, `"port":8080`, `"user":"user"`, `"pass":"pass"`} {
		if !strings.Contains(string(b), w) {
			t.Errorf("missing %s in %s", w, b)
		}
	}
}

func TestDNSGoesOverHTTPSOnlyForSocksAndHTTP(t *testing.T) {
	for link, want := range map[string]bool{
		"vless://id@10.8.0.1:80": false,
		"socks5://10.8.0.1:1080": true,
		"http://10.8.0.1:3128":   true,
	} {
		cfg, err := BuildXrayConfig(link, 1400)
		if err != nil {
			t.Fatal(err)
		}
		for _, s := range []string{`"protocol": "dns"`, dohServer, `"inboundTag": [`} {
			if strings.Contains(cfg, s) != want {
				t.Errorf("%s: contains %s = %v, want %v\n%s", link, s, !want, want, cfg)
			}
		}
		// The proxy stays the first outbound, which DoH and apps' traffic use.
		if i := strings.Index(cfg, `"tag": "proxy"`); want && i > strings.Index(cfg, `"tag": "dns-out"`) {
			t.Errorf("%s: proxy is not the first outbound\n%s", link, cfg)
		}
	}
}

func TestBadLinks(t *testing.T) {
	for _, link := range []string{"", "vmess://x@h:1", "vless://h:1", "vless://id@h", "vless://id@h:1?type=kcp", "vless://id@h:1?security=xtls",
		"socks5://h", "socks5://:1080", "http://h:0", "https://h", "ftp://h:21", "trojan://pw@h:443", "socks4://h:1080", "socks4a://h:1080"} {
		if _, err := proxyOutbound(link); err == nil {
			t.Errorf("expected error for %q", link)
		}
	}
}

func TestLinkWarnings(t *testing.T) {
	cases := map[string]bool{
		"vless://id@h.example:443?security=tls&sni=h.example":                 false,
		"vless://id@h.example:443?security=reality&pbk=k&sni=www.example.com": false,
		"vless://id@h.example:80?type=ws&path=%2F":                            true,
		"vless://id@h.example:80?security=none":                               true,
		"socks5://user:pass@p.example:1080":                                   true,
		"http://user:pass@p.example:8080":                                     true,
		"https://user:pass@p.example:443":                                     false,
		"socks5://p.example:1080":                                             false,
	}
	for link, want := range cases {
		out, err := proxyOutbound(link)
		if err != nil {
			t.Fatalf("%s: %v", link, err)
		}
		if got := len(linkWarnings(out)) > 0; got != want {
			t.Errorf("%s: warning = %v, want %v", link, got, want)
		}
	}
}

func TestPastedConfigListeners(t *testing.T) {
	tun := `{"protocol": "tun", "settings": {"name": "xray0"}}`
	cases := []struct {
		name, inbound string
		wantErr       bool
		wantWarnings  int
	}{
		{"tun only", tun, false, 0},
		{"no listen means every network", `{"tag": "socks-in", "protocol": "socks", "port": 10808}`, true, 0},
		{"all addresses", `{"protocol": "socks", "listen": "0.0.0.0", "port": 10808}`, true, 0},
		{"a LAN address", `{"protocol": "http", "listen": "192.168.1.20", "port": 8080}`, true, 0},
		{"loopback", `{"protocol": "socks", "listen": "127.0.0.1", "port": 10808}`, false, 1},
		{"localhost", `{"protocol": "socks", "listen": "localhost", "port": 10808}`, false, 1},
		{"IPv6 loopback", `{"protocol": "socks", "listen": "::1", "port": 10808}`, false, 1},
		{"abstract unix socket", `{"protocol": "socks", "listen": "@xray"}`, false, 1},
		{"unix socket file", `{"protocol": "socks", "listen": "/data/local/xray.sock"}`, false, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			jc, err := serial.DecodeJSONConfig(strings.NewReader(
				`{"inbounds": [` + tun + `, ` + c.inbound + `], "outbounds": [{"protocol": "freedom"}]}`))
			if err != nil {
				t.Fatal(err)
			}
			warnings, err := checkListeners(jc)
			if (err != nil) != c.wantErr {
				t.Fatalf("err = %v, want error: %v", err, c.wantErr)
			}
			if len(warnings) != c.wantWarnings {
				t.Fatalf("warnings = %q, want %d", warnings, c.wantWarnings)
			}
		})
	}
}

func TestStartRefusesPastedConfigListeningOnTheNetwork(t *testing.T) {
	cfg := `{"inbounds": [{"protocol": "socks", "port": 10808}], "outbounds": [{"protocol": "freedom"}]}`
	err := Start(-1, "10.9.0.2/24", "", 1500, 0, cfg)
	if err == nil {
		Stop()
		t.Fatal("Start accepted an inbound on every network")
	}
	if !strings.Contains(err.Error(), `inbound "socks"`) {
		t.Fatalf("error doesn't name the inbound: %v", err)
	}
	if IsRunning() {
		t.Fatal("engine running after the refusal")
	}
}
