package tsnet

// MARK: - Self-healing for a dead magicsock receive goroutine
//
// T-001 (2026-11-19): when the phone's default route is taken over by a proxy
// TUN, magicsock's UDP receive goroutines can exit with
// "recvfrom: software caused connection abort". The wireguard-go goroutine
// wrapper returns on that error, so nothing ever reopens the socket;
// tsnet's own rebind path hits "rebind-no-localaddr" while the proxy holds
// the default route and gives up. The node then stays up on DERP only:
// health picks it up but nothing acts on it, and udp=false stays until the
// whole node is stopped and started again.
//
// tailscale's health tracker notices the condition on its own (it compares
// each receive func's call counter once a minute and reports a warnable),
// and the warning reaches us as ipnstate.Status.Health - the same strings
// StatusJSON already reports. So the fix is here, not in the upstream
// module: when the warning stays up across two polls, stop and start the
// node once (state and prefs live on disk, so nobody logs in again), with a
// gap and an hourly cap so a persistent fault cannot turn into a restart
// loop. The decision policy lives in ./selfheal so it can be unit-tested on
// a desktop (this package only compiles for Android / Darwin / iOS).
//
// Cost, as measured on the device (minis-2026-11-19.log): a stop/start
// takes about 13 s, during which tailnet dials fail and traffic that could
// use the tailnet goes direct / through proxies. It fires at most once per
// selfheal.MinGap and only while a health warning says a receive func is
// gone; a watchdog that cannot restart reports it in the log and in
// StatusJSON (autoRestarts / lastAutoRestartReason) instead.

import (
	"context"
	"time"

	"github.com/OpenMinis/minis-rclone/tsnet/selfheal"
)

const (
	// healthPollInterval is how often Status is read. tailscale refreshes
	// its receive-func verdict once a minute, so polling faster only adds
	// load.
	healthPollInterval = 30 * time.Second
	// healthConfirmations is how many consecutive polls must carry the
	// warning. Two samples 30 s apart cannot be a transient taken while the
	// health tracker was itself mid-update.
	healthConfirmations = 2
	// healthStatusTimeout bounds one Status call; a wedged node must not
	// hold the watchdog forever.
	healthStatusTimeout = 10 * time.Second
)

// Watchdog state; every field is guarded by mu (tsnet.go).
var (
	watchGen  int           // bumped by every start / stop / restart
	watchStop chan struct{} // closed to end the current watchdog

	autoRestarts          int       // successful automatic restarts since process start
	lastAutoRestart       time.Time // zero until the first one
	lastAutoRestartReason string

	restarts selfheal.Limiter
)

// startWatchdogLocked arms a watchdog for the node that was just started and
// retires the previous one. mu must be held.
func startWatchdogLocked() {
	watchGen++
	gen := watchGen
	if watchStop != nil {
		close(watchStop)
	}
	stop := make(chan struct{})
	watchStop = stop
	a := args
	go watchdogLoop(gen, stop, a)
}

// stopWatchdogLocked retires the current watchdog; the next start arms a new
// one. mu must be held.
func stopWatchdogLocked() {
	watchGen++
	if watchStop != nil {
		close(watchStop)
		watchStop = nil
	}
}

func watchdogLoop(gen int, stop <-chan struct{}, a startArgs) {
	t := time.NewTicker(healthPollInterval)
	defer t.Stop()
	misses := 0
	var retryAt time.Time
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		if !retryAt.IsZero() && time.Now().Before(retryAt) {
			continue
		}
		reason, alive := nodeHealth(gen)
		if !alive {
			return
		}
		if reason == "" {
			misses = 0
			continue
		}
		misses++
		if misses < healthConfirmations {
			continue
		}
		misses = 0
		healed, err := restartAfterHealth(gen, a, reason)
		if healed {
			return // restartAfterHealth armed a watchdog for the new node
		}
		if err != nil {
			// Refused (rate limit) or failed: try again later rather than
			// dropping the watch, but do not hammer it.
			retryAt = time.Now().Add(selfheal.MinGap)
		}
	}
}

// nodeHealth returns the dead-receive-func warning carried by the node's
// health, if any, and whether the watchdog's node is still the one running.
func nodeHealth(gen int) (reason string, alive bool) {
	mu.Lock()
	s, cur := srv, watchGen
	mu.Unlock()
	if s == nil || cur != gen {
		return "", false
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "", true
	}
	ctx, cancel := context.WithTimeout(context.Background(), healthStatusTimeout)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", true
	}
	if st.BackendState != "Running" {
		// NeedsLogin / Starting / Stopped: a restart cannot help, and the
		// app owns those transitions.
		return "", true
	}
	return selfheal.ReceiveFuncWarning(st.Health), true
}

// restartAfterHealth stops and starts the node once, keeping its state dir
// and prefs. It reports whether the node was replaced (a new watchdog is
// armed in that case). mu is acquired here; this runs off the app's path.
func restartAfterHealth(gen int, a startArgs, reason string) (bool, error) {
	mu.Lock()
	defer mu.Unlock()
	if watchGen != gen || srv == nil {
		return false, nil // the app changed the node meanwhile; leave it
	}
	now := time.Now()
	if err := restarts.Allow(now); err != nil {
		logf("[Net][TS] self-heal needed (%s) but %v", reason, err)
		return false, err
	}
	old := srv
	srv = nil
	stopWatchdogLocked() // bumps watchGen; this goroutine is the one retiring
	if err := old.Close(); err != nil {
		logf("[Net][TS] self-heal: closing the failed node: %v", err)
	}
	invalidateRouteCache()
	t0 := time.Now()
	s := newServer(a)
	if err := s.Start(); err != nil {
		logf("[Net][TS] self-heal restart FAILED after %dms: %v", time.Since(t0).Milliseconds(), err)
		return false, err
	}
	srv = s
	restarts.Note(now)
	autoRestarts++
	lastAutoRestart = now
	lastAutoRestartReason = reason
	startWatchdogLocked()
	invalidateRouteCache()
	logf("[Net][TS] self-heal: node restarted in %dms after %q (#%d)", time.Since(t0).Milliseconds(), reason, autoRestarts)
	return true, nil
}
