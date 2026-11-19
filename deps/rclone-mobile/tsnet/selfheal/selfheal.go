// Package selfheal holds the policy the embedded Tailscale node applies when
// magicsock's UDP receive goroutines die under a proxy TUN (the full story is
// in ../../tsnet/watchdog.go).
//
// It lives in its own package so the policy can be unit-tested on a desktop:
// the parent package only compiles for Android, Darwin and iOS (tailscale's
// netmon exposes its interface getter and default-route setter under
// platform-specific file names), so `go test` for it has to run on a device.
package selfheal

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

// MinGap is the minimum time between two automatic restarts, and
// MaxPerHour caps how many a node may do in a rolling hour, so a fault that
// restarts cannot fix does not turn into a restart loop.
const (
	MinGap     = 10 * time.Minute
	MaxPerHour = 3
)

// ReceiveFuncWarning reports the health text that says a magicsock receive
// goroutine is gone, or "" when every receive func is alive.
//
// tailscale renders the warning from its warnable
// "magicsock-receive-func-error": "The MagicSock function ReceiveIPv4 is not
// running. You might experience connectivity issues." The warnable code is
// matched too in case a tailscale version surfaces codes instead of text.
func ReceiveFuncWarning(health []string) string {
	for _, h := range health {
		if strings.Contains(h, "MagicSock function") && strings.Contains(h, "not running") {
			return h
		}
		if strings.Contains(h, "magicsock-receive-func-error") {
			return h
		}
	}
	return ""
}

// Limiter enforces MinGap and MaxPerHour. It is safe for concurrent use, and
// callers inject the clock so tests do not sleep.
type Limiter struct {
	mu    sync.Mutex
	last  time.Time
	times []time.Time
}

// Allow reports whether a restart may happen at now, with the reason when it
// may not.
func (l *Limiter) Allow(now time.Time) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.last.IsZero() {
		if gap := now.Sub(l.last); gap < MinGap {
			return fmt.Errorf("the last automatic restart was %s ago", gap.Round(time.Second))
		}
	}
	cut := now.Add(-time.Hour)
	kept := l.times[:0]
	for _, t := range l.times {
		if t.After(cut) {
			kept = append(kept, t)
		}
	}
	l.times = kept
	if len(l.times) >= MaxPerHour {
		return fmt.Errorf("%d automatic restarts in the last hour", len(l.times))
	}
	return nil
}

// Note records a restart that did happen at now.
func (l *Limiter) Note(now time.Time) {
	l.mu.Lock()
	l.last = now
	l.times = append(l.times, now)
	l.mu.Unlock()
}
