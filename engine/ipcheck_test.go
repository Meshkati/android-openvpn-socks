package engine

import (
	"context"
	"crypto/x509"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

// startXray runs an Xray instance whose only outbound is freedom, so
// checkIP's requests go through Xray's dispatcher to a local server.
func startXray(t *testing.T) *core.Instance {
	t.Helper()
	xc, err := serial.LoadJSONConfig(strings.NewReader(`{"outbounds": [{"protocol": "freedom"}]}`))
	if err != nil {
		t.Fatal(err)
	}
	inst, err := core.New(xc)
	if err != nil {
		t.Fatal(err)
	}
	if err := inst.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { inst.Close() })
	return inst
}

func ipServer(t *testing.T, status int, body string) (*httptest.Server, *x509.CertPool) {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ip" || r.Header.Get("User-Agent") == "" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(status)
		fmt.Fprint(w, body)
	}))
	t.Cleanup(srv.Close)
	roots := x509.NewCertPool()
	roots.AddCert(srv.Certificate())
	return srv, roots
}

type lineLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *lineLog) add(s string) {
	l.mu.Lock()
	l.lines = append(l.lines, s)
	l.mu.Unlock()
}

func (l *lineLog) text() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "\n")
}

func TestCheckIPThroughXray(t *testing.T) {
	inst := startXray(t)
	srv, roots := ipServer(t, http.StatusOK, "203.0.113.7\n")

	var log lineLog
	ip, err := checkIP(context.Background(), inst, srv.URL+"/ip", roots, log.add)
	if err != nil {
		t.Fatalf("checkIP: %v\nlog:\n%s", err, log.text())
	}
	if ip != "203.0.113.7" {
		t.Errorf("ip = %q, want 203.0.113.7", ip)
	}
	text := log.text()
	for _, want := range []string{
		"through Xray's routing",
		"TLS handshake with 127.0.0.1",
		"TLS 1.3",
		"> GET /ip HTTP/1.1",
		"> User-Agent: curl/",
		"< HTTP/1.1 200 OK",
		"< Content-Type: text/plain",
		"< 203.0.113.7",
		"coming from 203.0.113.7",
	} {
		if !strings.Contains(text, want) {
			t.Errorf("log has no %q:\n%s", want, text)
		}
	}
}

func TestCheckIPRejectsBadAnswers(t *testing.T) {
	inst := startXray(t)
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"not an IP", http.StatusOK, "<html>hello</html>", "didn't answer with an IP address"},
		{"server error", http.StatusBadGateway, "203.0.113.7", "answered 502 Bad Gateway"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv, roots := ipServer(t, tc.status, tc.body)
			var log lineLog
			_, err := checkIP(context.Background(), inst, srv.URL+"/ip", roots, log.add)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want %q", err, tc.want)
			}
			if !strings.Contains(log.text(), "failed: ") {
				t.Errorf("log doesn't say it failed:\n%s", log.text())
			}
		})
	}
}

func TestCheckIPUntrustedCertificate(t *testing.T) {
	inst := startXray(t)
	srv, _ := ipServer(t, http.StatusOK, "203.0.113.7")
	var log lineLog
	_, err := checkIP(context.Background(), inst, srv.URL+"/ip", x509.NewCertPool(), log.add)
	if err == nil || !strings.Contains(err.Error(), "certificate") {
		t.Fatalf("err = %v, want a certificate error", err)
	}
}

func TestCheckIPWithoutEngine(t *testing.T) {
	if IsRunning() {
		t.Skip("engine running")
	}
	if _, err := CheckIP("https://ifconfig.me/ip", nil); err == nil || !strings.Contains(err.Error(), "not running") {
		t.Fatalf("err = %v, want 'not running'", err)
	}
}
