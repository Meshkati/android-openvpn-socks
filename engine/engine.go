// Package engine chains Xray-core behind an OpenVPN 3 tunnel. It is built
// with gomobile into an AAR for the Android app.
//
// Packet flow on the device:
//
//	apps -> VpnService TUN -> Xray "tun" inbound -> VLESS outbound
//	     -> tunnelNet (gVisor) -> packet socket -> OpenVPN 3 core -> network
//
// OpenVPN 3 does not get a real TUN: its tun_builder_establish() returns one
// end of a socketpair, and the other end is handed to Start as ovpnFd. Xray's
// system dialer is replaced so the VLESS connection is made through a
// userspace TCP/IP stack sitting on that socket, i.e. inside OpenVPN.
package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"

	applog "github.com/xtls/xray-core/app/log"
	commlog "github.com/xtls/xray-core/common/log"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

// Logger receives log lines from the engine and from Xray-core.
type Logger interface {
	Log(line string)
}

var (
	logMu  sync.Mutex
	logger Logger

	mu       sync.Mutex
	instance *core.Instance
	tunnel   *tunnelNet
)

func init() {
	err := applog.RegisterHandlerCreator(applog.LogType_Console,
		func(applog.LogType, applog.HandlerCreatorOptions) (commlog.Handler, error) {
			return commlog.NewLogger(func() commlog.Writer { return logWriter{} }), nil
		})
	if err != nil {
		fmt.Fprintln(os.Stderr, "engine: register log handler:", err)
	}
}

type logWriter struct{}

func (logWriter) Write(s string) error { logLine(s); return nil }
func (logWriter) Close() error         { return nil }

func logLine(s string) {
	logMu.Lock()
	l := logger
	logMu.Unlock()
	if l != nil {
		l.Log(s)
	} else {
		fmt.Fprintln(os.Stderr, s)
	}
}

// SetLogger installs the receiver for log lines (nil logs to stderr).
func SetLogger(l Logger) {
	logMu.Lock()
	logger = l
	logMu.Unlock()
}

// Start brings the chain up.
//
//   - ovpnFd: the app's end of the packet socketpair given to OpenVPN 3.
//     If Start succeeds the engine owns it and closes it in Stop; on error
//     it stays with the caller.
//   - ovpnAddrs: comma separated CIDRs the OpenVPN server assigned, e.g.
//     "10.8.0.6/24,fd00::1000/64".
//   - ovpnDNS: comma separated DNS servers pushed by the OpenVPN server, used
//     to resolve the VLESS server name inside the tunnel (may be empty).
//   - tunFd: the VpnService TUN fd read by Xray's tun inbound; the caller
//     keeps ownership. Pass 0 when config brings its own inbound.
//   - config: a vless:// link, or a complete Xray JSON config.
func Start(ovpnFd int32, ovpnAddrs, ovpnDNS string, mtu int32, tunFd int32, config string) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		return errors.New("engine already running")
	}

	addrs, err := parseList(ovpnAddrs, netip.ParsePrefix)
	if err != nil {
		return fmt.Errorf("tunnel address: %w", err)
	}
	dns, err := parseList(ovpnDNS, netip.ParseAddr)
	if err != nil {
		return fmt.Errorf("tunnel DNS: %w", err)
	}
	if mtu <= 0 {
		mtu = 1500
	}

	jsonConfig := config
	if !strings.HasPrefix(strings.TrimSpace(config), "{") {
		if jsonConfig, err = BuildXrayConfig(config, mtu); err != nil {
			return err
		}
	}
	xc, err := serial.LoadJSONConfig(strings.NewReader(jsonConfig))
	if err != nil {
		return fmt.Errorf("xray config: %w", err)
	}

	tn, err := newTunnelNet(int(ovpnFd), uint32(mtu), addrs, dns)
	if err != nil {
		return err
	}
	// Every socket Xray opens (the VLESS connection, its DNS, ...) now goes
	// through the OpenVPN tunnel instead of the Android network.
	internet.UseAlternativeSystemDialer(&tunnelDialer{tn})

	os.Setenv(platform.TunFdKey, strconv.Itoa(int(tunFd)))
	inst, err := core.New(xc)
	if err == nil {
		err = inst.Start()
		if err != nil {
			inst.Close()
		}
	}
	if err != nil {
		internet.UseAlternativeSystemDialer(nil)
		tn.closeStack()
		return fmt.Errorf("start xray: %w", err)
	}
	instance, tunnel = inst, tn
	logLine(fmt.Sprintf("engine: Xray %s up, tunnel %v dns %v", core.Version(), addrs, dns))
	return nil
}

// tunnelDialer is Xray's system dialer while the engine runs.
type tunnelDialer struct{ n *tunnelNet }

func (d *tunnelDialer) Dial(ctx context.Context, _ xnet.Address, dest xnet.Destination, _ *internet.SocketConfig) (net.Conn, error) {
	return d.n.DialContext(ctx, dest.Network.SystemString(), dest.NetAddr())
}

func (d *tunnelDialer) DestIpAddress() net.IP { return nil }

// Stop shuts Xray down and releases the OpenVPN packet socket.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if instance == nil {
		return nil
	}
	err := instance.Close()
	internet.UseAlternativeSystemDialer(nil)
	tunnel.Close()
	instance, tunnel = nil, nil
	return err
}

// IsRunning reports whether Start succeeded and Stop has not been called.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// XrayVersion returns the bundled Xray-core version.
func XrayVersion() string { return core.Version() }

func parseList[T any](s string, parse func(string) (T, error)) ([]T, error) {
	var out []T
	for _, f := range strings.Split(s, ",") {
		if f = strings.TrimSpace(f); f == "" {
			continue
		}
		v, err := parse(f)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}
