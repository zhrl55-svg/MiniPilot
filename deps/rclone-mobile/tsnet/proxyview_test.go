package tsnet

import "testing"

// The hub tells a failure at the node from a failure at a peer that is
// off: it needs the peer's state from the same snapshot the dial logs
// use.
func TestPeerOnlineKnowsThePeerState(t *testing.T) {
	r := routeCache{running: true, peers: map[string]*peerBrief{
		"100.95.180.77":              {name: "asus", online: false, path: "idle"},
		"buntu.saola-mintaka.ts.net": {name: "buntu", online: true, path: "relay hkg"},
		"buntu":                      {name: "buntu", online: true, path: "relay hkg"},
	}}
	// A peer the control plane says is down: known, offline.
	known, online := peerOnline(r, "100.95.180.77")
	if !known || online {
		t.Fatalf("the offline peer: known=%v online=%v, want true false", known, online)
	}
	// A peer that is up: known, online.
	known, online = peerOnline(r, "buntu.saola-mintaka.ts.net")
	if !known || !online {
		t.Fatalf("the online peer: known=%v online=%v, want true true", known, online)
	}
	// The short name of a peer works as well.
	known, online = peerOnline(r, "buntu")
	if !known || !online {
		t.Fatalf("the short name: known=%v online=%v, want true true", known, online)
	}
	// Names are matched case-insensitively and without the trailing dot.
	known, online = peerOnline(r, "BUNTU.Saola-Mintaka.TS.NET.")
	if !known || !online {
		t.Fatalf("the mixed-case name: known=%v online=%v, want true true", known, online)
	}
	// A host that is not a peer of this tailnet (a public name behind an
	// exit node, an address of an accepted subnet route) is unknown - it
	// must never be taken for an offline peer.
	known, online = peerOnline(r, "example.com")
	if known {
		t.Fatalf("a public host: known=%v online=%v, want false false", known, online)
	}
}

// The hub asks the node through this interface.
var _ interface {
	PeerOnline(host string) (known bool, online bool)
} = ProxyView{}
