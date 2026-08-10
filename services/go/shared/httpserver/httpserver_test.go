package httpserver

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
)

// bigHeader returns an Authorization header value of roughly n bytes.
func bigHeader(n int) string {
	pad := strings.Repeat("x", n)
	return "Bearer " + pad
}

// serveAndGet boots the given app on an ephemeral port, sends a raw HTTP GET
// with the supplied Authorization header, and returns the parsed response.
// It uses raw TCP because app.Test(req) bypasses fasthttp's request parser,
// which is where 431 originates.
func serveAndGet(t *testing.T, app *fiber.App, authHeader string) *http.Response {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ready := make(chan struct{})
	go func() {
		close(ready)
		_ = app.Listener(ln)
	}()
	<-ready
	// Give the goroutine a moment to actually start accepting.
	time.Sleep(20 * time.Millisecond)
	t.Cleanup(func() { _ = app.Shutdown() })

	conn, err := net.DialTimeout("tcp", ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))

	req := fmt.Sprintf(
		"GET /health HTTP/1.1\r\nHost: x\r\nAuthorization: %s\r\nConnection: close\r\n\r\n",
		authHeader,
	)
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatalf("write: %v", err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	// Drain and close so http.ReadResponse doesn't leak the connection.
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	return resp
}

// TestFactory_AcceptsLargeHeader guards the buffer widening: an 8 KiB
// Authorization header (well above Fiber's 4 KiB default) must be accepted
// by a server built via New.
func TestFactory_AcceptsLargeHeader(t *testing.T) {
	app := New(Options{AppName: "test"})
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("ok") })
	resp := serveAndGet(t, app, bigHeader(8*1024))
	if resp.StatusCode == http.StatusRequestHeaderFieldsTooLarge {
		t.Fatalf("expected non-431, got %d — factory ReadBufferSize is not applied", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}

// TestBareFiber_Rejects431 is the sanity check on the harness itself: a bare
// fiber.New() must reject the same 8 KiB header, so TestFactory_AcceptsLargeHeader
// is genuinely proving the factory did something (not silently passing because
// 8 KiB happens to fit in some default we didn't know about).
func TestBareFiber_Rejects431(t *testing.T) {
	app := fiber.New()
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("ok") })
	resp := serveAndGet(t, app, bigHeader(8*1024))
	if resp.StatusCode != http.StatusRequestHeaderFieldsTooLarge {
		t.Fatalf("expected 431 from bare Fiber, got %d — test is not measuring what we think", resp.StatusCode)
	}
}

// TestFactory_431LogHookFires guards the fallback described in the plan: if
// this test starts failing after a Fiber upgrade, switch to a fasthttp-level
// Server.Logger hook. A header well past the 32 KiB buffer must both come back
// as 431 AND leave a "[httpserver] 431" line in the application log.
func TestFactory_431LogHookFires(t *testing.T) {
	var (
		buf bytes.Buffer
		mu  sync.Mutex
	)
	prevOut := log.Writer()
	prevFlags := log.Flags()
	log.SetOutput(syncWriter{w: &buf, mu: &mu})
	t.Cleanup(func() {
		log.SetOutput(prevOut)
		log.SetFlags(prevFlags)
	})

	app := New(Options{AppName: "test"})
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("ok") })
	resp := serveAndGet(t, app, bigHeader(60*1024))
	if resp.StatusCode != http.StatusRequestHeaderFieldsTooLarge {
		t.Fatalf("expected 431 for a 60 KiB header, got %d — buffer over-provisioned?", resp.StatusCode)
	}

	// Fiber's ErrorHandler dispatch happens on the request goroutine, which
	// has already returned by the time http.ReadResponse hands us the body —
	// so the log write should be visible immediately. Poll briefly just in
	// case the writer is racing.
	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		mu.Lock()
		got := buf.String()
		mu.Unlock()
		if strings.Contains(got, "[httpserver] 431 headers-too-large") {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	final := buf.String()
	mu.Unlock()
	t.Fatalf("expected '[httpserver] 431 headers-too-large' in log, got %q — apply the fasthttp-level Logger fallback", final)
}

// syncWriter serialises writes to the underlying buffer so the test can read
// it without racing the request goroutine's logger write.
type syncWriter struct {
	w  io.Writer
	mu *sync.Mutex
}

func (s syncWriter) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.w.Write(p)
}
