package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync/atomic"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const nicID tcpip.NICID = 1

// tunnelNet is a userspace TCP/IP stack whose only interface is the packet
// socket that OpenVPN 3 core uses as its "tun". Every connection dialed
// through it is carried inside the OpenVPN tunnel, without touching the
// Android routing table.
type tunnelNet struct {
	fd       int
	stack    *stack.Stack
	hasV4    bool
	hasV6    bool
	resolver *net.Resolver
}

// newTunnelNet attaches a gVisor stack to fd, which must carry raw IP packets
// (one packet per read/write), configured with the addresses the OpenVPN
// server assigned. DNS lookups go to dns through the tunnel; with no DNS
// servers the system resolver is used.
func newTunnelNet(fd int, mtu uint32, addrs []netip.Prefix, dns []netip.Addr) (*tunnelNet, error) {
	if len(addrs) == 0 {
		return nil, errors.New("tunnel has no IP address")
	}
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: mtu})
	if err != nil {
		return nil, fmt.Errorf("fdbased endpoint: %w", err)
	}
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	if e := s.CreateNIC(nicID, ep); e != nil {
		s.Close()
		return nil, fmt.Errorf("create NIC: %v", e)
	}

	t := &tunnelNet{fd: fd, stack: s}
	var routes []tcpip.Route
	for _, p := range addrs {
		a := p.Addr().Unmap()
		pa := tcpip.ProtocolAddress{
			AddressWithPrefix: tcpip.AddressWithPrefix{Address: tcpip.AddrFromSlice(a.AsSlice()), PrefixLen: p.Bits()},
		}
		if a.Is4() {
			pa.Protocol = ipv4.ProtocolNumber
			if !t.hasV4 {
				routes = append(routes, tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: nicID})
			}
			t.hasV4 = true
		} else {
			pa.Protocol = ipv6.ProtocolNumber
			if !t.hasV6 {
				routes = append(routes, tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: nicID})
			}
			t.hasV6 = true
		}
		if e := s.AddProtocolAddress(nicID, pa, stack.AddressProperties{}); e != nil {
			s.Close()
			return nil, fmt.Errorf("add address %v: %v", p, e)
		}
	}
	s.SetRouteTable(routes)

	t.resolver = net.DefaultResolver
	if len(dns) > 0 {
		var next atomic.Uint32
		t.resolver = &net.Resolver{
			PreferGo: true,
			// The address picked from resolv.conf is ignored: queries always go
			// to the tunnel's DNS servers, rotating between them.
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				server := dns[int(next.Add(1)-1)%len(dns)]
				return t.dialIP(ctx, network, server.Unmap(), 53)
			},
		}
	}
	return t, nil
}

// DialContext dials a "tcp" or "udp" address (host may be a domain name)
// through the tunnel.
func (t *tunnelNet) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return nil, fmt.Errorf("bad port in %q", address)
	}

	var ips []netip.Addr
	if ip, err := netip.ParseAddr(host); err == nil {
		ips = []netip.Addr{ip.Unmap()}
	} else {
		found, err := t.resolver.LookupNetIP(ctx, "ip", host)
		if err != nil {
			return nil, fmt.Errorf("resolve %s: %w", host, err)
		}
		for _, ip := range found {
			ip = ip.Unmap()
			if (ip.Is4() && t.hasV4) || (ip.Is6() && t.hasV6) {
				ips = append(ips, ip)
			}
		}
		if len(ips) == 0 {
			return nil, fmt.Errorf("resolve %s: no address usable inside the tunnel", host)
		}
	}

	var lastErr error
	for _, ip := range ips {
		c, err := t.dialIP(ctx, network, ip, uint16(port))
		if err == nil {
			return c, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (t *tunnelNet) dialIP(ctx context.Context, network string, ip netip.Addr, port uint16) (net.Conn, error) {
	fa := tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFromSlice(ip.AsSlice()), Port: port}
	proto := ipv4.ProtocolNumber
	if ip.Is6() {
		proto = ipv6.ProtocolNumber
	}
	switch network {
	case "tcp", "tcp4", "tcp6":
		return gonet.DialContextTCP(ctx, t.stack, fa, proto)
	case "udp", "udp4", "udp6":
		return gonet.DialUDP(t.stack, nil, &fa, proto)
	}
	return nil, fmt.Errorf("unsupported network %q", network)
}

// Close tears the stack down and closes the packet socket.
func (t *tunnelNet) Close() {
	t.closeStack()
	unix.Close(t.fd)
}

// closeStack tears the stack down but leaves the packet socket open.
func (t *tunnelNet) closeStack() {
	t.stack.Close()
	t.stack.Wait()
}
