package selfheal

import (
	"testing"
	"time"
)

func TestReceiveFuncWarning(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{"ipv4 receive func gone", "The MagicSock function ReceiveIPv4 is not running. You might experience connectivity issues.", true},
		{"ipv6 receive func gone", "The MagicSock function ReceiveIPv6 is not running. You might experience connectivity issues.", true},
		{"warnable code surfaced", "magicsock-receive-func-error: ReceiveIPv4", true},
		{"login warning", "You are logged out. The last login error was: register request: http 410: auth path not found", false},
		{"other health", "Tailscale is starting", false},
		{"empty", "", false},
	}
	for _, c := range cases {
		var health []string
		if c.in != "" {
			health = []string{c.in}
		}
		got := ReceiveFuncWarning(health)
		if c.want && got == "" {
			t.Errorf("%s: warning not detected", c.name)
		}
		if !c.want && got != "" {
			t.Errorf("%s: false positive %q", c.name, got)
		}
	}
	// The scan must reach entries past the first, and nil health (a node that
	// reports no warnings at all) must not match.
	health := []string{
		"some warning",
		"The MagicSock function ReceiveIPv4 is not running. You might experience connectivity issues.",
	}
	if got := ReceiveFuncWarning(health); got == "" {
		t.Error("match in a later entry not found")
	}
	if got := ReceiveFuncWarning(nil); got != "" {
		t.Errorf("nil health matched: %q", got)
	}
}

func TestLimiter(t *testing.T) {
	var l Limiter
	t0 := time.Unix(1_700_000_000, 0)
	if err := l.Allow(t0); err != nil {
		t.Fatalf("first restart refused: %v", err)
	}
	l.Note(t0)
	if err := l.Allow(t0.Add(time.Minute)); err == nil {
		t.Fatal("min gap not enforced")
	}
	at := t0.Add(MinGap)
	if err := l.Allow(at); err != nil {
		t.Fatalf("restart after the gap refused: %v", err)
	}
	l.Note(at)
	at = at.Add(MinGap)
	l.Note(at)
	// Three notes in the last hour: the next one after the gap is refused.
	if err := l.Allow(at.Add(MinGap)); err == nil {
		t.Fatal("hourly cap not enforced")
	}
	// An hour later the stale entries are gone and a restart is allowed.
	if err := l.Allow(t0.Add(2 * time.Hour)); err != nil {
		t.Fatalf("stale entries not pruned: %v", err)
	}
}
