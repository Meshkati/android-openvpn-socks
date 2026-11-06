package engine

import (
	"context"
	"crypto/x509"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func releaseServer(t *testing.T) (*httptest.Server, *x509.CertPool) {
	t.Helper()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/latest":
			if r.Header.Get("User-Agent") != "ovpnvless/1.2.3 (Android)" || r.Header.Get("Accept") != "application/vnd.github+json" {
				http.Error(w, "missing headers", http.StatusBadRequest)
				return
			}
			w.Write([]byte(`{"tag_name": "v1.2.4"}`))
		case "/big":
			w.Write(make([]byte, fetchLimit+1))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	roots := x509.NewCertPool()
	roots.AddCert(srv.Certificate())
	return srv, roots
}

func TestFetchThroughXray(t *testing.T) {
	inst := startXray(t)
	srv, roots := releaseServer(t)
	headers := "Accept: application/vnd.github+json\nUser-Agent: ovpnvless/1.2.3 (Android)\n"
	body, err := fetch(context.Background(), inst, srv.URL+"/latest", headers, roots)
	if err != nil {
		t.Fatal(err)
	}
	if body != `{"tag_name": "v1.2.4"}` {
		t.Fatalf("body = %q", body)
	}
}

func TestFetchFailures(t *testing.T) {
	inst := startXray(t)
	srv, roots := releaseServer(t)
	cases := map[string]struct{ path, headers, want string }{
		"not 200":      {"/missing", "", "404"},
		"too big":      {"/big", "", "over"},
		"bad header":   {"/latest", "no colon here", "colon"},
		"untrusted CA": {"/latest", "", "certificate"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			pool := roots
			if name == "untrusted CA" {
				pool = x509.NewCertPool()
			}
			_, err := fetch(context.Background(), inst, srv.URL+c.path, c.headers, pool)
			if err == nil || !strings.Contains(err.Error(), c.want) {
				t.Fatalf("err = %v, want one mentioning %q", err, c.want)
			}
		})
	}
}

func TestFetchWithoutEngine(t *testing.T) {
	if _, err := Fetch("https://api.github.com/", ""); err == nil || !strings.Contains(err.Error(), "not running") {
		t.Fatalf("err = %v", err)
	}
}
