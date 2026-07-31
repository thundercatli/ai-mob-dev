// Package frpcllib is a gomobile-friendly wrapper that runs frp's STCP visitor
// *in-process* (iOS cannot fork/exec child processes the way the Android app does with its
// packaged libfrpc.so). Each tunnel is an independent client.Service so tunnels keep starting
// and stopping without cutting one another's live sessions — the same property the Android app
// buys by running one frpc process per tunnel.
//
// Build with gomobile (see scripts/build_frpc_ios.sh):
//
//	gomobile bind -target=ios -o ../Frameworks/Frpclib.xcframework .
//
// gomobile exports only exported funcs/methods whose signatures use primitive types or other
// exported types, so the surface here is deliberately flat (string/int/bool/error).
package frpcllib

import (
	"context"
	"strings"
	"sync"

	"github.com/fatedier/frp/client"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/source"
	frlog "github.com/fatedier/frp/pkg/util/log"
	golog "github.com/fatedier/golib/log"
)

// Tunnel states are plain strings because gomobile cannot transport Go enums.
const (
	StateStopped  = "STOPPED"
	StateStarting = "STARTING"
	StateRunning  = "RUNNING"
	StateError    = "ERROR"
)

const maxLogLines = 200

// Runtime owns all visitor tunnels, keyed by the caller's tunnel id. It is the in-process
// analogue of the Android app's FrpcRuntime + FrpcVisitorService: observable status and a
// bounded per-tunnel log that the UI polls.
type Runtime struct {
	mu      sync.Mutex
	tunnels map[string]*tunnel
	sink    *logSink // single global sink; frp logs through one package-global logger
}

type tunnel struct {
	id        string
	svc       *client.Service
	cancel    context.CancelFunc
	state     string
	lastError string
	logs      *ringBuffer
	visitor   string // visitor name, to attribute global log lines
	done      chan struct{}
}

// NewRuntime creates an empty runtime and points frp's package-global logger at a capture sink
// so the UI can show the same lines the Android app reads off frpc's stdout.
func NewRuntime() *Runtime {
	rt := &Runtime{
		tunnels: make(map[string]*tunnel),
		sink:    newLogSink(),
	}
	// frp's pkg/util/log exposes Logger (*golib/log.Logger); rebuild it so every line flows
	// through our sink. golib/log calls Write([]byte) on a plain io.Writer (log.go:147-156).
	frlog.Logger = golog.New(
		golog.WithOutput(rt.sink),
		golog.WithLevel(golog.InfoLevel),
		golog.WithCaller(true),
	)
	return rt
}

// StartTunnel runs an STCP visitor for the given frps endpoint in its own goroutine and returns
// immediately. The visitor listens on 127.0.0.1:bindPort; the SSH client then dials that local
// address, exactly as on Android. Calling StartTunnel with an id already running first stops
// the old one (so the local port is freed).
func (rt *Runtime) StartTunnel(id, serverAddr string, serverPort int, token, serverName, secretKey string, bindPort int) error {
	rt.mu.Lock()
	rt.stopLocked(id)
	rt.mu.Unlock()

	common := &v1.ClientCommonConfig{
		ServerAddr:    serverAddr,
		ServerPort:    serverPort,
		User:          "aidevmob",
		LoginFailExit: ptr(false), // keep retrying login on network blips, like Android's restart loop
	}
	if token != "" {
		common.Auth.Method = v1.AuthMethodToken
		common.Auth.Token = token
	}
	// Embed the tunnel id into the visitor name so per-tunnel log lines can be attributed.
	visitorName := "ios-" + shortID(id)
	visitor := &v1.STCPVisitorConfig{
		VisitorBaseConfig: v1.VisitorBaseConfig{
			Name:       visitorName,
			Type:       "stcp",
			ServerName: serverName,
			SecretKey:  secretKey,
			BindAddr:   "127.0.0.1",
			BindPort:   bindPort,
		},
	}
	svc, err := client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: source.NewAggregator(source.NewConfigSource()),
	})
	if err != nil {
		return err
	}
	if err := svc.UpdateAllConfigurer(nil, []v1.VisitorConfigurer{visitor}); err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	t := &tunnel{
		id:      id,
		svc:     svc,
		cancel:  cancel,
		state:   StateStarting,
		logs:    newRingBuffer(maxLogLines),
		visitor: visitorName,
		done:    make(chan struct{}),
	}

	rt.mu.Lock()
	rt.tunnels[id] = t
	rt.sink.register(t)
	rt.mu.Unlock()

	go func() {
		defer close(t.done)
		if err := svc.Run(ctx); err != nil && ctx.Err() == nil {
			rt.setState(id, StateError, err.Error())
			return
		}
		rt.setState(id, StateStopped, "")
	}()
	return nil
}

// StopTunnel stops one tunnel and frees its local port.
func (rt *Runtime) StopTunnel(id string) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.stopLocked(id)
}

// Status reports the tunnel's current state.
func (rt *Runtime) Status(id string) string {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if t := rt.tunnels[id]; t != nil {
		return t.state
	}
	return StateStopped
}

// LastError returns the most recent error for a tunnel in the ERROR state.
func (rt *Runtime) LastError(id string) string {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if t := rt.tunnels[id]; t != nil {
		return t.lastError
	}
	return ""
}

// IsRunning is a convenience for callers that only need the up/down bit.
func (rt *Runtime) IsRunning(id string) bool { return rt.Status(id) == StateRunning }

// Logs returns the most recent log lines for a tunnel, newest last. Mirrors the Android app's
// FrpcRuntime.logSnapshot().
func (rt *Runtime) Logs(id string) string {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if t := rt.tunnels[id]; t != nil {
		return t.logs.String()
	}
	return ""
}

// --- internals -------------------------------------------------------------

func (rt *Runtime) stopLocked(id string) {
	t := rt.tunnels[id]
	if t == nil {
		return
	}
	t.cancel()
	t.svc.Close()
	rt.sink.unregister(t)
	delete(rt.tunnels, id)
}

func (rt *Runtime) setState(id, state, errMsg string) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	if t := rt.tunnels[id]; t != nil {
		t.state = state
		if errMsg != "" {
			t.lastError = errMsg
		}
	}
}

// markStartingRunning flips every tunnel currently STARTING to RUNNING. Called when frp reports
// a successful server login. Each tunnel is its own Service = its own frps connection, so a login
// marker belongs to whichever tunnel(s) are mid-startup. This mirrors Android's stdout grep of
// "login to server success" / "start visitor success" in FrpcVisitorService.pumpOutput.
func (rt *Runtime) markStartingRunning() {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	for _, t := range rt.tunnels {
		if t.state == StateStarting {
			t.state = StateRunning
		}
	}
}

func ptr[T any](v T) *T { return &v }

func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

// --- log capture -----------------------------------------------------------
// frp logs through one package-global *golib/log.Logger whose output we replaced with a sink.
// golib/log writes one fully-formatted line per call to io.Writer.Write (log.go:147-156), so the
// sink receives whole lines and can attribute them by the visitor name embedded in each line.

type logSink struct {
	mu       sync.Mutex
	tunnels  []*tunnel
}

func newLogSink() *logSink { return &logSink{} }

func (s *logSink) register(t *tunnel) {
	s.mu.Lock()
	s.tunnels = append(s.tunnels, t)
	s.mu.Unlock()
}

func (s *logSink) unregister(t *tunnel) {
	s.mu.Lock()
	out := s.tunnels[:0]
	for _, x := range s.tunnels {
		if x != t {
			out = append(out, x)
		}
	}
	s.tunnels = out
	s.mu.Unlock()
}

// Write implements io.Writer. golib/log passes complete formatted lines.
func (s *logSink) Write(p []byte) (int, error) {
	line := strings.TrimSpace(string(p))
	if line == "" {
		return len(p), nil
	}
	s.mu.Lock()
	targets := append([]*tunnel(nil), s.tunnels...)
	s.mu.Unlock()

	// Attribute the line to whichever tunnel's visitor name it mentions; unattributed lines
	// (e.g. the bare "login to server success") go to all, since the global logger can't tell
	// them apart and every STARTING tunnel benefits from the RUNNING marker.
	anyMatched := false
	for _, t := range targets {
		if strings.Contains(line, t.visitor) {
			t.logs.append(line)
			anyMatched = true
		}
	}
	if !anyMatched {
		for _, t := range targets {
			t.logs.append(line)
		}
	}
	// RUNNING markers — same heuristic as Android's FrpcVisitorService.pumpOutput.
	if strings.Contains(line, "login to server success") || strings.Contains(line, "start visitor success") {
		// setState needs the runtime; the tunnel has no back-ref, so use a closure capture.
		for _, t := range targets {
			if t.state == StateStarting {
				t.state = StateRunning
			}
		}
	}
	return len(p), nil
}

// ringBuffer is a bounded, newline-delimited log buffer.
type ringBuffer struct {
	mu    sync.Mutex
	lines []string
	max   int
}

func newRingBuffer(max int) *ringBuffer { return &ringBuffer{max: max} }

func (r *ringBuffer) append(line string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, line)
	for len(r.lines) > r.max {
		r.lines = r.lines[1:]
	}
}

func (r *ringBuffer) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}
