package engine

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/netip"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
)

const ipCheckTimeout = 20 * time.Second

var (
	checkMu sync.Mutex // one IP check at a time

	// Cancels the running check; guarded by mu. Stop calls it so the check
	// never dials after the engine is gone.
	checkCancel context.CancelFunc

	// Also receives the engine's and Xray's log lines while a check runs;
	// guarded by logMu.
	checkLogger Logger
)

// CheckIP fetches url, a "what is my IP" service such as
// https://ifconfig.me/ip, through the running Xray instance, the way apps'
// traffic leaves: Xray's routing, then the VLESS outbound, which reaches its
// server inside the OpenVPN tunnel. It returns the IP address in the reply.
//
// The app is excluded from its own VPN, so a request made any other way from
// the app would go out over the real network instead.
//
// Every step is logged to l, and so are the engine's and Xray's own log
// lines while the check runs (prefixed "xray: "), so l gets the whole story
// of a failure.
func CheckIP(url string, l Logger) (string, error) {
	logf := logLine
	if l != nil {
		logf = l.Log
	}
	if !checkMu.TryLock() {
		return "", errors.New("an IP check is already running")
	}
	defer checkMu.Unlock()

	mu.Lock()
	inst := instance
	if inst == nil {
		mu.Unlock()
		logf("failed: the engine is not running")
		return "", errors.New("the engine is not running")
	}
	ctx, cancel := context.WithTimeout(context.Background(), ipCheckTimeout)
	checkCancel = cancel
	mu.Unlock()
	defer func() {
		mu.Lock()
		checkCancel = nil
		mu.Unlock()
		cancel()
	}()

	if l != nil {
		logMu.Lock()
		checkLogger = l
		logMu.Unlock()
		defer func() {
			logMu.Lock()
			checkLogger = nil
			logMu.Unlock()
		}()
	}
	return checkIP(ctx, inst, url, nil, logf)
}

// checkIP does CheckIP's request through inst. roots replaces the system
// CA pool when not nil (tests).
func checkIP(ctx context.Context, inst *core.Instance, rawURL string, roots *x509.CertPool, logf func(string)) (string, error) {
	start := time.Now()
	logStep := func(format string, a ...any) {
		logf(fmt.Sprintf("%5dms  ", time.Since(start).Milliseconds()) + fmt.Sprintf(format, a...))
	}
	fail := func(err error) (string, error) {
		err = describeCheckError(ctx, err)
		logStep("failed: %v", err)
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return fail(err)
	}
	// ifconfig.me answers command-line clients with plain text.
	req.Header.Set("User-Agent", "curl/8.7.1")
	req.Header.Set("Accept", "text/plain")
	host := req.URL.Hostname()

	logStep("GET %s through Xray, as apps' traffic goes", rawURL)
	transport := &http.Transport{
		DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
			h, p, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			port, err := xnet.PortFromString(p)
			if err != nil {
				return nil, err
			}
			dest := xnet.TCPDestination(xnet.ParseAddress(h), port)
			if dest.Address.Family().IsDomain() {
				logStep("open %s through Xray's routing; the outbound's server looks up %s", dest, h)
			} else {
				logStep("open %s through Xray's routing", dest)
			}
			// ctx, not the dial context: the stream lives as long as the check.
			return core.Dial(ctx, inst, dest)
		},
		TLSClientConfig:   &tls.Config{RootCAs: roots},
		DisableKeepAlives: true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		// Show a redirect instead of following it.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}

	trace := &httptrace.ClientTrace{
		TLSHandshakeStart: func() { logStep("TLS handshake with %s", host) },
		TLSHandshakeDone: func(cs tls.ConnectionState, err error) {
			if err != nil {
				logStep("TLS handshake failed: %v", err)
				return
			}
			logStep("%s, %s", tls.VersionName(cs.Version), tls.CipherSuiteName(cs.CipherSuite))
			if len(cs.PeerCertificates) > 0 {
				c := cs.PeerCertificates[0]
				logStep("certificate checked for %s, issued by %s, valid until %s",
					host, issuerName(c), c.NotAfter.UTC().Format(time.DateOnly))
			}
		},
		GotConn: func(httptrace.GotConnInfo) {
			logStep("> %s %s HTTP/1.1", req.Method, req.URL.RequestURI())
		},
		WroteHeaderField: func(key string, value []string) {
			logStep("> %s: %s", key, strings.Join(value, ", "))
		},
		WroteRequest: func(info httptrace.WroteRequestInfo) {
			if info.Err == nil {
				logStep("request sent, waiting for the answer")
			}
		},
		GotFirstResponseByte: func() { logStep("first byte of the answer") },
	}

	resp, err := client.Do(req.WithContext(httptrace.WithClientTrace(ctx, trace)))
	if err != nil {
		return fail(err)
	}
	defer resp.Body.Close()
	logStep("< %s %s", resp.Proto, resp.Status)
	keys := make([]string, 0, len(resp.Header))
	for k := range resp.Header {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		logStep("< %s: %s", k, strings.Join(resp.Header[k], ", "))
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return fail(fmt.Errorf("reading the answer: %w", err))
	}
	text := strings.TrimSpace(string(body))
	for i, line := range strings.Split(text, "\n") {
		if i == 20 {
			logStep("< …")
			break
		}
		logStep("< %s", strings.TrimRight(line, "\r"))
	}

	if resp.StatusCode != http.StatusOK {
		return fail(fmt.Errorf("%s answered %s", host, resp.Status))
	}
	ip, err := netip.ParseAddr(text)
	if err != nil {
		return fail(fmt.Errorf("%s didn't answer with an IP address", host))
	}
	ip = ip.Unmap()
	logStep("done: %s sees this connection coming from %s", host, ip)
	return ip.String(), nil
}

// describeCheckError turns the transport's errors into a sentence.
func describeCheckError(ctx context.Context, err error) error {
	switch {
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		return fmt.Errorf("no answer within %v", ipCheckTimeout)
	case errors.Is(ctx.Err(), context.Canceled):
		return errors.New("the engine stopped")
	}
	// Drop url.Error's `Get "https://…":` prefix; the log already has the URL.
	var ue *url.Error
	if errors.As(err, &ue) {
		err = ue.Err
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, io.ErrClosedPipe) {
		return fmt.Errorf("the connection through Xray closed (%v); Xray's log says why", err)
	}
	return err
}

// issuerName is e.g. "Google Trust Services YR2".
func issuerName(c *x509.Certificate) string {
	name := c.Issuer.CommonName
	if len(c.Issuer.Organization) > 0 && !strings.Contains(name, c.Issuer.Organization[0]) {
		name = strings.TrimSpace(c.Issuer.Organization[0] + " " + name)
	}
	return name
}
