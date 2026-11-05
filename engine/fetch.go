package engine

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/xtls/xray-core/core"
)

const (
	fetchTimeout = 20 * time.Second
	fetchLimit   = 1 << 20
)

// Fetch GETs url through the running Xray instance, the way apps' traffic
// leaves (see CheckIP), and returns the body of a 200 answer. headers holds
// "Name: value" lines.
//
// While VLESS is connected the app is excluded from its own VPN, so its own
// requests (the update check) must go this way: a plain request from the app
// would go out over the Wi-Fi or mobile network and show the server the
// phone's real IP address.
func Fetch(url, headers string) (string, error) {
	mu.Lock()
	inst, ctx := instance, runCtx
	mu.Unlock()
	if inst == nil {
		return "", errors.New("the engine is not running")
	}
	// Canceled by Stop: nothing may dial once the tunnel dialer is gone.
	ctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	return fetch(ctx, inst, url, headers, nil)
}

// fetch does Fetch's request through inst. roots replaces the system CA pool
// when not nil (tests).
func fetch(ctx context.Context, inst *core.Instance, rawURL, headers string, roots *x509.CertPool) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(headers, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			return "", fmt.Errorf("header line without a colon: %q", line)
		}
		req.Header.Set(strings.TrimSpace(name), strings.TrimSpace(value))
	}

	transport := xrayTransport(ctx, inst, roots, nil)
	defer transport.CloseIdleConnections()
	resp, err := (&http.Client{Transport: transport}).Do(req)
	if err != nil {
		return "", describeCheckError(ctx, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%s answered %s", req.URL.Hostname(), resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, fetchLimit+1))
	if err != nil {
		return "", fmt.Errorf("reading the answer: %w", err)
	}
	if len(body) > fetchLimit {
		return "", fmt.Errorf("the answer is over %d bytes", fetchLimit)
	}
	return string(body), nil
}
