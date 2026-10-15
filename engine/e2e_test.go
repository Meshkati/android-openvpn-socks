//go:build e2e

// End-to-end test of the chain, driven by hosttest/run-e2e.sh: the packet
// socket comes from a real OpenVPN 3 session (fd 3, see ovpn_hosttest.cpp)
// and the VLESS server is reachable only inside the tunnel. A SOCKS inbound
// stands in for the Android tun inbound.
package engine

import (
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestE2E(t *testing.T) {
	fd, err := strconv.Atoi(os.Getenv("OVPN_FD"))
	if err != nil {
		t.Skip("OVPN_FD not set; run hosttest/run-e2e.sh")
	}
	mtu, _ := strconv.Atoi(os.Getenv("OVPN_MTU"))

	cfg, err := buildConfig(os.Getenv("VLESS_LINK"), map[string]any{
		"tag": "socks-in", "protocol": "socks", "listen": "127.0.0.1", "port": 10808,
		"settings": map[string]any{"udp": true},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := Start(int32(fd), os.Getenv("OVPN_ADDRS"), os.Getenv("OVPN_DNS"), int32(mtu), 0, cfg); err != nil {
		t.Fatal(err)
	}
	defer Stop()

	client := &http.Client{
		Timeout:   30 * time.Second,
		Transport: &http.Transport{Proxy: http.ProxyURL(&url.URL{Scheme: "socks5", Host: "127.0.0.1:10808"})},
	}
	get := func(u string) []byte {
		t.Helper()
		resp, err := client.Get(u)
		if err != nil {
			t.Fatalf("GET %s: %v", u, err)
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(resp.Body)
		if err != nil || resp.StatusCode != 200 {
			t.Fatalf("GET %s: status %d, %v", u, resp.StatusCode, err)
		}
		return b
	}

	base := os.Getenv("TARGET_URL")
	for i := 0; i < 3; i++ {
		if b := get(base + "/marker.txt"); !strings.Contains(string(b), "chain-ok") {
			t.Fatalf("unexpected body %q", b)
		}
	}

	start := time.Now()
	big := get(base + "/big.bin")
	want, _ := os.ReadFile(os.Getenv("BIG_FILE"))
	if sha256.Sum256(big) != sha256.Sum256(want) {
		t.Fatalf("big.bin corrupted: got %d bytes, want %d", len(big), len(want))
	}
	secs := time.Since(start).Seconds()
	fmt.Printf("E2E OK: %d bytes through OpenVPN+VLESS in %.2fs (%.1f MB/s)\n",
		len(big), secs, float64(len(big))/secs/1e6)
}
