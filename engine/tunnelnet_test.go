package engine

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"slices"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// tunnelWithPeer returns a tunnelNet on one end of a packet socketpair, as
// OpenVPN 3 gives the engine, and a gVisor stack on the other end standing in
// for the network behind the OpenVPN server, with peerAddrs assigned.
func tunnelWithPeer(t *testing.T, dns []netip.Addr, peerAddrs ...string) (*tunnelNet, *stack.Stack) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	tn, err := newTunnelNet(fds[0], 1500, []netip.Prefix{netip.MustParsePrefix("10.9.0.2/24")}, dns)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tn.Close)

	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fds[1]}, MTU: 1500})
	if err != nil {
		t.Fatal(err)
	}
	peer := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{udp.NewProtocol},
	})
	t.Cleanup(func() { peer.Close(); peer.Wait(); unix.Close(fds[1]) })
	if e := peer.CreateNIC(nicID, ep); e != nil {
		t.Fatal(e)
	}
	for _, a := range peerAddrs {
		pa := tcpip.ProtocolAddress{
			Protocol:          ipv4.ProtocolNumber,
			AddressWithPrefix: tcpip.AddrFromSlice(netip.MustParseAddr(a).AsSlice()).WithPrefix(),
		}
		if e := peer.AddProtocolAddress(nicID, pa, stack.AddressProperties{}); e != nil {
			t.Fatal(e)
		}
	}
	peer.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}})
	return tn, peer
}

// serveDNS answers A queries on the peer's server:53 with answer, and every
// other type with no records. Each asked name is sent to asked.
func serveDNS(t *testing.T, peer *stack.Stack, server string, answer netip.Addr, asked chan<- string) {
	t.Helper()
	laddr := &tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFromSlice(netip.MustParseAddr(server).AsSlice()), Port: 53}
	conn, err := gonet.DialUDP(peer, laddr, nil, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := conn.ReadFrom(buf)
			if err != nil {
				return
			}
			name, reply, ok := dnsReply(buf[:n], answer)
			if !ok {
				continue
			}
			select {
			case asked <- name:
			default:
			}
			conn.WriteTo(reply, from)
		}
	}()
}

// dnsReply builds the answer to a one-question query: an A record with
// answer for type A, nothing for other types.
func dnsReply(q []byte, answer netip.Addr) (name string, reply []byte, ok bool) {
	if len(q) < 12 || binary.BigEndian.Uint16(q[4:]) != 1 {
		return "", nil, false
	}
	i := 12
	for i < len(q) && q[i] != 0 {
		name += string(q[i+1:i+1+int(q[i])]) + "."
		i += 1 + int(q[i])
	}
	end := i + 5 // the root label, type and class
	if end > len(q) {
		return "", nil, false
	}
	qtype := binary.BigEndian.Uint16(q[i+1:])
	reply = append(reply, q[:2]...)               // ID
	reply = append(reply, 0x81, 0x80, 0, 1, 0, 0) // response, recursion; one question
	reply = append(reply, 0, 0, 0, 0)             // no authority or additional records
	reply = append(reply, q[12:end]...)
	if qtype == 1 {
		reply[7] = 1 // one answer
		reply = append(reply, 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
		reply = append(reply, answer.AsSlice()...)
	}
	return name, reply, true
}

func TestLookupsGoIntoTheTunnelWithoutPushedDNS(t *testing.T) {
	tn, peer := tunnelWithPeer(t, nil, "10.9.0.1", "1.1.1.1")
	if want := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}; !slices.Equal(tn.dns, want) {
		t.Fatalf("dns = %v, want the IPv4 fallback servers %v (the tunnel has no IPv6)", tn.dns, want)
	}
	asked := make(chan string, 4)
	serveDNS(t, peer, "1.1.1.1", netip.MustParseAddr("10.9.0.1"), asked)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// The resolver rotates between 1.1.1.1 and 8.8.8.8; only 1.1.1.1 answers.
	var ips []netip.Addr
	var err error
	for range 3 {
		if ips, err = tn.resolver.LookupNetIP(ctx, "ip4", "vless.example."); err == nil {
			break
		}
	}
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if len(ips) != 1 || ips[0] != netip.MustParseAddr("10.9.0.1") {
		t.Fatalf("got %v, want 10.9.0.1", ips)
	}
	if name := <-asked; name != "vless.example." {
		t.Fatalf("the server inside the tunnel was asked for %q", name)
	}
}

func TestDefaultResolverGoesIntoTheTunnelWhileInstalled(t *testing.T) {
	dns := []netip.Addr{netip.MustParseAddr("10.9.0.53")}
	tn, peer := tunnelWithPeer(t, dns, "10.9.0.1", "10.9.0.53")
	asked := make(chan string, 4)
	serveDNS(t, peer, "10.9.0.53", netip.MustParseAddr("10.9.0.1"), asked)

	useTunnelResolver(tn)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip4", "via-default.example.")
	restoreResolver()
	if err != nil {
		t.Fatalf("lookup: %v", err)
	}
	if len(ips) != 1 || ips[0] != netip.MustParseAddr("10.9.0.1") {
		t.Fatalf("got %v, want 10.9.0.1", ips)
	}
	if name := <-asked; name != "via-default.example." {
		t.Fatalf("the tunnel's DNS server was asked for %q", name)
	}
	if net.DefaultResolver.PreferGo || net.DefaultResolver.Dial != nil {
		t.Fatal("restoreResolver left net.DefaultResolver changed")
	}
}
