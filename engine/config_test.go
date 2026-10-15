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

func TestBadLinks(t *testing.T) {
	for _, link := range []string{"", "vmess://x@h:1", "vless://h:1", "vless://id@h", "vless://id@h:1?type=kcp", "vless://id@h:1?security=xtls"} {
		if _, err := vlessOutbound(link); err == nil {
			t.Errorf("expected error for %q", link)
		}
	}
}
