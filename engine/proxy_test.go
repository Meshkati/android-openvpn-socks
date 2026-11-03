package engine

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

// startXrayWith runs the config buildConfig makes for link, with a SOCKS
// inbound standing in for the Android tun inbound, and Xray's own dialer.
func startXrayWith(t *testing.T, link string) *core.Instance {
	t.Helper()
	cfg, err := buildConfig(link, map[string]any{
		"tag": "test-in", "protocol": "socks", "listen": "127.0.0.1", "port": freePort(t),
	})
	if err != nil {
		t.Fatal(err)
	}
	xc, err := serial.LoadJSONConfig(strings.NewReader(cfg))
	if err != nil {
		t.Fatalf("%v\n%s", err, cfg)
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

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// pipe copies both ways between a and b until either side closes.
func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { io.Copy(a, b); done <- struct{}{} }()
	go func() { io.Copy(b, a); done <- struct{}{} }()
	<-done
	a.Close()
	b.Close()
}

// connectProxy is an HTTP proxy that only does CONNECT, with Basic auth.
// Every CONNECT target it is asked for is sent to asked, if not nil.
func connectProxy(t *testing.T, user, pass string, asked chan<- string) (addr string, used *atomic.Int32) {
	t.Helper()
	used = new(atomic.Int32)
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				req, err := http.ReadRequest(bufio.NewReader(c))
				if err != nil {
					return
				}
				if asked != nil {
					select {
					case asked <- req.Host:
					default:
					}
				}
				if req.Method != http.MethodConnect || req.Header.Get("Proxy-Authorization") != want {
					fmt.Fprint(c, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
					return
				}
				up, err := net.Dial("tcp", req.Host)
				if err != nil {
					fmt.Fprint(c, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
					return
				}
				used.Add(1)
				fmt.Fprint(c, "HTTP/1.1 200 Connection established\r\n\r\n")
				pipe(c, up)
			}()
		}
	}()
	return l.Addr().String(), used
}

// socks5Proxy is a SOCKS5 server for CONNECT, with username/password auth.
func socks5Proxy(t *testing.T, user, pass string) (addr string, used *atomic.Int32) {
	t.Helper()
	used = new(atomic.Int32)
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	serve := func(c net.Conn) error {
		defer c.Close()
		r := bufio.NewReader(c)
		head := make([]byte, 2)
		if _, err := io.ReadFull(r, head); err != nil || head[0] != 5 {
			return fmt.Errorf("greeting: %v", err)
		}
		methods := make([]byte, head[1])
		io.ReadFull(r, methods)
		if !strings.Contains(string(methods), "\x02") {
			c.Write([]byte{5, 0xff})
			return nil
		}
		c.Write([]byte{5, 2})
		// RFC 1929: ver, ulen, user, plen, pass.
		readField := func() string {
			n, _ := r.ReadByte()
			b := make([]byte, n)
			io.ReadFull(r, b)
			return string(b)
		}
		r.ReadByte()
		u, p := readField(), readField()
		if u != user || p != pass {
			c.Write([]byte{1, 1})
			return nil
		}
		c.Write([]byte{1, 0})

		req := make([]byte, 4)
		if _, err := io.ReadFull(r, req); err != nil || req[1] != 1 {
			return fmt.Errorf("request: %v", err)
		}
		var host string
		switch req[3] {
		case 1:
			ip := make([]byte, 4)
			io.ReadFull(r, ip)
			host = net.IP(ip).String()
		case 3:
			host = readField()
		case 4:
			ip := make([]byte, 16)
			io.ReadFull(r, ip)
			host = net.IP(ip).String()
		}
		portBytes := make([]byte, 2)
		io.ReadFull(r, portBytes)
		up, err := net.Dial("tcp", net.JoinHostPort(host, fmt.Sprint(binary.BigEndian.Uint16(portBytes))))
		if err != nil {
			c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
			return nil
		}
		used.Add(1)
		c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
		pipe(&bufferedConn{c, r}, up)
		return nil
	}
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go serve(c)
		}
	}()
	return l.Addr().String(), used
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(b []byte) (int, error) { return c.r.Read(b) }

func TestTrafficThroughSocksAndHTTPProxies(t *testing.T) {
	srv, roots := ipServer(t, http.StatusOK, "198.51.100.4\n")
	socksAddr, socksUsed := socks5Proxy(t, "alice", "s3cret")
	httpAddr, httpUsed := connectProxy(t, "bob", "p@ss", nil)
	creds := base64.StdEncoding.EncodeToString([]byte("alice:s3cret"))

	for _, tc := range []struct {
		link string
		used *atomic.Int32
	}{
		{"socks5://alice:s3cret@" + socksAddr + "#test", socksUsed},
		{"socks://" + creds + "@" + socksAddr, socksUsed},
		{"http://bob:p%40ss@" + httpAddr, httpUsed},
	} {
		t.Run(tc.link, func(t *testing.T) {
			before := tc.used.Load()
			inst := startXrayWith(t, tc.link)
			var log lineLog
			ip, err := checkIP(context.Background(), inst, srv.URL+"/ip", roots, log.add)
			if err != nil {
				t.Fatalf("checkIP: %v\nlog:\n%s", err, log.text())
			}
			if ip != "198.51.100.4" {
				t.Errorf("ip = %q", ip)
			}
			if tc.used.Load() == before {
				t.Error("the request didn't go through the proxy")
			}
		})
	}
}

func TestWrongProxyPasswordFails(t *testing.T) {
	srv, roots := ipServer(t, http.StatusOK, "198.51.100.4\n")
	socksAddr, _ := socks5Proxy(t, "alice", "s3cret")
	httpAddr, _ := connectProxy(t, "bob", "p@ss", nil)
	for _, link := range []string{"socks5://alice:wrong@" + socksAddr, "http://bob:wrong@" + httpAddr} {
		inst := startXrayWith(t, link)
		var log lineLog
		if _, err := checkIP(context.Background(), inst, srv.URL+"/ip", roots, log.add); err == nil {
			t.Errorf("%s: checkIP succeeded with a wrong password", link)
		}
	}
}

// Apps' DNS reaches the inbound as UDP to port 53. It must not be sent to
// the HTTP proxy as UDP, which it can't carry, but asked of the DoH server
// through the proxy.
func TestAppsDNSGoesToDoHThroughTheProxy(t *testing.T) {
	asked := make(chan string, 8)
	httpAddr, _ := connectProxy(t, "bob", "p@ss", asked)
	dnsPort := freePort(t)
	cfg, err := buildConfig("http://bob:p%40ss@"+httpAddr, map[string]any{
		"tag": "test-in", "protocol": "dokodemo-door", "listen": "127.0.0.1", "port": dnsPort,
		"settings": map[string]any{"address": "1.1.1.1", "port": 53, "network": "udp"},
	})
	if err != nil {
		t.Fatal(err)
	}
	xc, err := serial.LoadJSONConfig(strings.NewReader(cfg))
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

	c, err := net.Dial("udp", fmt.Sprintf("127.0.0.1:%d", dnsPort))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	query := func(id uint16, qType uint16) {
		// Header: id, RD, one question; then example.com, qType, class IN.
		q := binary.BigEndian.AppendUint16(nil, id)
		q = append(q, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
		q = append(q, "\x07example\x03com\x00"...)
		q = binary.BigEndian.AppendUint16(q, qType)
		q = binary.BigEndian.AppendUint16(q, 1)
		if _, err := c.Write(q); err != nil {
			t.Fatal(err)
		}
	}

	// A TXT query is answered right away, empty.
	query(0x1234, 16)
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	b := make([]byte, 512)
	n, err := c.Read(b)
	if err != nil {
		t.Fatalf("no answer to the TXT query: %v", err)
	}
	if n < 12 || binary.BigEndian.Uint16(b) != 0x1234 || b[3]&0x0f != 0 || binary.BigEndian.Uint16(b[6:]) != 0 {
		t.Errorf("TXT answer = % x, want an empty NOERROR answer", b[:n])
	}

	// An A query becomes a DoH request through the proxy.
	query(0x4321, 1)
	select {
	case host := <-asked:
		if host != "1.1.1.1:443" {
			t.Errorf("proxy was asked for %s, want 1.1.1.1:443", host)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the A query never reached the proxy")
	}
}
