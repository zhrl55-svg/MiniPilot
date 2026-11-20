package nethub

import (
	"errors"
	"strings"
	"testing"
)

func (c *captureLog) count(sub string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, l := range c.lines {
		if strings.Contains(l, sub) {
			n++
		}
	}
	return n
}

// [FIX-261019-30] One alert after AlertAfter consecutive path failures, one
// recovery line on the next success, nothing for failures that are not the
// path's fault.
func TestExitAlertStreaks(t *testing.T) {
	logs := &captureLog{}
	h := New(nil, logs.logf)
	up := &Upstream{ID: "p-1", Name: "Office proxy"}
	proxyD := Decision{Kind: "proxy", Upstream: up}
	tsD := Decision{Kind: "tailscale"}
	refused := &StageError{Stage: "proxy-connect", Class: ClassRefused, Err: errors.New("dial tcp 10.0.0.2:8080: connect: connection refused")}

	for i := 0; i < AlertAfter-1; i++ {
		h.recordExit(proxyD, "example.com:443", refused)
	}
	if logs.count("[Net][Alert]") != 0 {
		t.Fatalf("alerted before %d failures", AlertAfter)
	}
	h.recordExit(proxyD, "example.com:443", refused)
	line := logs.find("[Net][Alert]")
	for _, want := range []string{"exit=proxy id=p-1 ", "failures=3", "stage=proxy-connect", "class=refused", "name=Office_proxy", "err=dial tcp"} {
		if !strings.Contains(line, want) {
			t.Fatalf("alert %q lacks %q", line, want)
		}
	}
	for i := 0; i < 5; i++ {
		h.recordExit(proxyD, "example.com:443", refused)
	}
	if n := logs.count("[Net][Alert]"); n != 1 {
		t.Fatalf("one alert per streak, got %d", n)
	}

	h.recordExit(proxyD, "example.com:443", nil)
	if logs.find("[Net][Recovered] exit=proxy id=p-1") == "" {
		t.Fatal("no recovery line after success")
	}
	h.recordExit(proxyD, "example.com:443", nil)
	if n := logs.count("[Net][Recovered]"); n != 1 {
		t.Fatalf("recovery logged %d times", n)
	}

	// The proxy works but cannot reach the target: not the proxy's fault.
	target := &StageError{Stage: "proxy-target", Class: ClassProxyReject, Err: errors.New("proxy CONNECT x:443: 502")}
	for i := 0; i < 5; i++ {
		h.recordExit(proxyD, "example.com:443", target)
	}
	// Direct dials never count.
	direct := &StageError{Stage: "direct", Class: ClassRefused, Err: errors.New("refused")}
	for i := 0; i < 5; i++ {
		h.recordExit(Decision{Kind: "direct"}, "example.com:443", direct)
	}
	if n := logs.count("[Net][Alert]"); n != 1 {
		t.Fatalf("proxy-target / direct failures alerted (%d alerts)", n)
	}

	// A success in between breaks the streak.
	tsErr := &StageError{Stage: "tailnet", Class: ClassTimeout, Err: errors.New("i/o timeout")}
	h.recordExit(tsD, "100.95.180.77:3425", tsErr)
	h.recordExit(tsD, "100.95.180.77:3425", tsErr)
	h.recordExit(tsD, "100.95.180.77:3425", nil)
	h.recordExit(tsD, "100.95.180.77:3425", tsErr)
	h.recordExit(tsD, "100.95.180.77:3425", tsErr)
	if logs.find("exit=tailscale failures") != "" {
		t.Fatal("streak survived a success")
	}
	h.recordExit(tsD, "100.95.180.77:3425", tsErr)
	if !strings.Contains(logs.find("[Net][Alert] exit=tailscale"), "failures=3 stage=tailnet class=timeout err=i/o timeout") {
		t.Fatalf("tailscale alert: %q", logs.find("[Net][Alert] exit=tailscale"))
	}

	// A changed config clears streaks; an identical one keeps them.
	h.recordExit(proxyD, "example.com:443", refused)
	h.recordExit(proxyD, "example.com:443", refused)
	h.Configure(Config{DefaultRoute: "direct"})
	h.Configure(Config{DefaultRoute: "direct"})
	h.recordExit(proxyD, "example.com:443", refused)
	if n := logs.count("[Net][Alert] exit=proxy"); n != 1 {
		t.Fatalf("streak survived a config change (%d proxy alerts)", n)
	}
	h.recordExit(proxyD, "example.com:443", refused)
	h.Configure(Config{DefaultRoute: "direct"})
	h.recordExit(proxyD, "example.com:443", refused)
	if n := logs.count("[Net][Alert] exit=proxy"); n != 2 {
		t.Fatalf("an unchanged config reset the streak (%d proxy alerts)", n)
	}
}

func TestExitAlertFieldsStayOnOneLine(t *testing.T) {
	logs := &captureLog{}
	h := New(nil, logs.logf)
	d := Decision{Kind: "proxy", Upstream: &Upstream{ID: "a b=c", Name: "x\ny"}}
	e := &StageError{Stage: "proxy-handshake", Class: ClassOther, Err: errors.New("line1\nline2 " + strings.Repeat("z", 300))}
	for i := 0; i < AlertAfter; i++ {
		h.recordExit(d, "example.com:443", e)
	}
	l := logs.find("[Net][Alert]")
	if strings.Contains(l, "\n") || !strings.Contains(l, "id=a_b_c ") || !strings.Contains(l, "name=x_y ") || !strings.HasSuffix(l, "…") {
		t.Fatalf("bad alert line %q", l)
	}
}

// MARK: - FIX-261120-01: an offline peer is not a fault of the node
//
// The ASUS PC was off while the phone's tailnet was healthy: every dial to
// it timed out, three in a row, and the home screen showed "tailscale keeps
// failing". Dialing a machine that is off is EXPECTED to fail; only a
// failure at an ONLINE peer says something about the node.

// peerTailnet is a Tailnet that knows which of its peers are online.
type peerTailnet struct {
	fakeTailnet
	peers map[string]bool // host -> online
}

func (p *peerTailnet) PeerOnline(host string) (known bool, online bool) {
	on, ok := p.peers[host]
	return ok, on
}

// streakOf: the consecutive-failure streak recorded for a decision.
func streakOf(h *Hub, d Decision) int {
	h.exits.mu.Lock()
	defer h.exits.mu.Unlock()
	return h.exits.streak[exitKey(d)]
}

func peerHub(t *testing.T, peers map[string]bool) (*Hub, *captureLog) {
	t.Helper()
	logs := &captureLog{}
	ts := &peerTailnet{fakeTailnet: fakeTailnet{running: true}, peers: peers}
	return New(ts, logs.logf), logs
}

// A tailnet failure at a peer the tailnet knows is offline does not count
// towards the streak and raises no banner.
func TestExitAlertIgnoresFailuresToAnOfflinePeer(t *testing.T) {
	h, logs := peerHub(t, map[string]bool{
		"asus.saola-mintaka.ts.net":  false, // the PC is off
		"buntu.saola-mintaka.ts.net": true,
	})
	d := Decision{Kind: "tailscale", Rule: "tailnet"}
	tsErr := &StageError{Stage: "tailnet", Class: ClassTimeout, Err: errors.New("i/o timeout")}
	for i := 0; i < 5; i++ {
		h.recordExit(d, "asus.saola-mintaka.ts.net:8443", tsErr)
	}
	if n := logs.count("[Net][Alert]"); n != 0 {
		t.Fatalf("an offline peer raised %d alerts", n)
	}
	if n := streakOf(h, d); n != 0 {
		t.Fatalf("streak = %d, want 0: an offline peer must not count", n)
	}
	// The streak of a healthy peer is untouched by it: two failures at the
	// online peer and one more still make three.
	for i := 0; i < AlertAfter-1; i++ {
		h.recordExit(d, "buntu.saola-mintaka.ts.net:8787", tsErr)
	}
	if n := logs.count("[Net][Alert]"); n != 0 {
		t.Fatalf("alerted before %d failures", AlertAfter)
	}
	h.recordExit(d, "buntu.saola-mintaka.ts.net:8787", tsErr)
	if !strings.Contains(logs.find("[Net][Alert] exit=tailscale"), "failures=3 stage=tailnet class=timeout") {
		t.Fatalf("no alert for the online peer: %q", logs.find("[Net][Alert] exit=tailscale"))
	}
}

// A tailnet failure at a peer that IS online is a fault of the path: it
// counts and alerts as before.
func TestExitAlertStillCountsFailuresToAnOnlinePeer(t *testing.T) {
	h, logs := peerHub(t, map[string]bool{"buntu.saola-mintaka.ts.net": true})
	d := Decision{Kind: "tailscale", Rule: "tailnet"}
	tsErr := &StageError{Stage: "tailnet", Class: ClassTimeout, Err: errors.New("i/o timeout")}
	for i := 0; i < AlertAfter-1; i++ {
		h.recordExit(d, "buntu.saola-mintaka.ts.net:8787", tsErr)
	}
	if n := logs.count("[Net][Alert]"); n != 0 {
		t.Fatalf("alerted before %d failures", AlertAfter)
	}
	h.recordExit(d, "buntu.saola-mintaka.ts.net:8787", tsErr)
	line := logs.find("[Net][Alert] exit=tailscale")
	if !strings.Contains(line, "failures=3 stage=tailnet class=timeout err=i/o timeout") {
		t.Fatalf("tailscale alert: %q", line)
	}
	if n := streakOf(h, d); n != AlertAfter {
		t.Fatalf("streak = %d, want %d", n, AlertAfter)
	}
}

// A host the tailnet does not know (a public name behind an exit node, an
// address of an accepted subnet route) is not an offline peer: its failures
// still count.
func TestExitAlertCountsFailuresToUnknownHosts(t *testing.T) {
	h, logs := peerHub(t, map[string]bool{"asus.saola-mintaka.ts.net": false})
	d := Decision{Kind: "tailscale", Rule: "tailnet"}
	tsErr := &StageError{Stage: "tailnet", Class: ClassTimeout, Err: errors.New("i/o timeout")}
	for i := 0; i < AlertAfter; i++ {
		h.recordExit(d, "example.com:443", tsErr)
	}
	if n := logs.count("[Net][Alert] exit=tailscale"); n != 1 {
		t.Fatalf("alerts for an unknown host = %d, want 1", n)
	}
}

// The proxy branch is untouched: its failures count whatever the peers of
// the tailnet are doing.
func TestExitAlertProxyBranchIsUnchanged(t *testing.T) {
	h, logs := peerHub(t, map[string]bool{"asus.saola-mintaka.ts.net": false})
	d := Decision{Kind: "proxy", Upstream: &Upstream{ID: "p-1", Name: "Office proxy"}}
	refused := &StageError{Stage: "proxy-connect", Class: ClassRefused, Err: errors.New("dial tcp 10.0.0.2:8080: connect: connection refused")}
	for i := 0; i < AlertAfter; i++ {
		h.recordExit(d, "asus.saola-mintaka.ts.net:8443", refused)
	}
	if n := logs.count("[Net][Alert] exit=proxy"); n != 1 {
		t.Fatalf("proxy alerts = %d, want 1", n)
	}
	if n := streakOf(h, d); n != AlertAfter {
		t.Fatalf("proxy streak = %d, want %d", n, AlertAfter)
	}
}
