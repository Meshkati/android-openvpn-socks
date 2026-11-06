package engine

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/xtls/xray-core/infra/conf/serial"
)

// FuzzBuildXrayConfig: a pasted link must never panic the engine (a panic
// takes the app's process down with it), here or in Xray's own config
// builder, which Start runs next. What it accepts must be JSON.
// go test -fuzz=FuzzBuildXrayConfig -fuzztime=5m explores beyond the seeds.
func FuzzBuildXrayConfig(f *testing.F) {
	for _, seed := range []string{
		"vless://0f8e2a3c-1111-4222-8333-123456789abc@edge.example.org:443?type=ws&security=tls&sni=a.example&path=%2Fws&fp=chrome#name",
		"vless://id@[2001:db8::1]:8443?type=grpc&serviceName=svc&security=reality&pbk=abc&sid=12&flow=xtls-rprx-vision",
		"vless://id@h.example:443?type=xhttp&mode=packet-up&security=tls&alpn=h2,http/1.1&ech=AAA",
		"vless://id@h.example:80?type=tcp&headerType=http&host=a.example,b.example",
		"socks5://alex.smith:hunter22@proxy.example.org:1080#office",
		"socks://dXNlcjpwYXNz@1.2.3.4:1080",
		"http://proxy.example.org:8080@alex.smith:hunter22",
		"https://user:p%40ss@proxy.example.org:443?sni=s.example&fp=chrome",
		"socks4://1.2.3.4:1080",
		"",
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, link string) {
		cfg, err := BuildXrayConfig(link, 1500)
		if err != nil {
			return
		}
		if !json.Valid([]byte(cfg)) {
			t.Fatalf("invalid JSON for %q", link)
		}
		serial.LoadJSONConfig(strings.NewReader(cfg)) // may refuse it, must not panic
	})
}
