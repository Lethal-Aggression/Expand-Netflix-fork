package main

import (
	"context"
	"errors"
	"github.com/anacrolix/torrent"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

func TestDiskBudgetIncludesIncompleteAndConcurrentWrites(t *testing.T) {
	c, err := newDiskCache(t.TempDir(), 64)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			p := diskPiece{c, string(rune('a' + i)), 16}
			_, err := p.WriteAt([]byte{1}, 0)
			if err != nil && !errors.Is(err, syscall.ENOSPC) {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	if got := c.Info(); got.Filled > 64 || got.NumItems != 4 {
		t.Fatalf("budget escaped: %+v", got)
	}
}
func TestRollingEvictionPreservesHeaderTailAndRewind(t *testing.T) {
	c, _ := newDiskCache(t.TempDir(), 1024)
	for i := 0; i < 16; i++ {
		p := diskPiece{c, "hash-" + itoa(i), 16}
		if _, err := p.WriteAt([]byte{1}, 0); err != nil {
			t.Fatal(err)
		}
		if i != 2 {
			_ = p.MarkComplete()
		}
	}
	c.evict("hash", 0, 16, 10, 4, 2)
	for _, i := range []int{0, 1, 2, 8, 9, 10, 11, 12, 13, 14, 15} {
		if !(&diskPiece{c, "hash-" + itoa(i), 16}).Completion().Ok {
			t.Fatal(i)
		}
		if c.entries["hash-"+itoa(i)] == nil {
			t.Fatalf("protected/incomplete %d evicted", i)
		}
	}
	for _, i := range []int{3, 4, 5, 6, 7} {
		if c.entries["hash-"+itoa(i)] != nil {
			t.Fatalf("watched %d retained", i)
		}
	}
}
func itoa(i int) string {
	if i < 10 {
		return string(rune('0' + i))
	}
	return "1" + string(rune('0'+i-10))
}
func TestControlAuthenticationAndMethod(t *testing.T) {
	calls := 0
	h := authenticated(strings.Repeat("x", 40), http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	for _, test := range []struct {
		method, token string
		status        int
	}{{"POST", "", 401}, {"POST", "wrong", 401}, {"GET", strings.Repeat("x", 40), 405}, {"POST", strings.Repeat("x", 40), 200}} {
		r := httptest.NewRequest(test.method, "http://localhost/wipe", nil)
		r.Header.Set("X-Engine-Token", test.token)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != test.status {
			t.Fatalf("got %d want %d", w.Code, test.status)
		}
	}
	if calls != 1 {
		t.Fatal("unauthorized request reached control")
	}
}

type waitingReader struct{}

func (waitingReader) Read([]byte) (int, error) { panic("uncancellable Read used") }
func (waitingReader) ReadContext(ctx context.Context, _ []byte) (int, error) {
	<-ctx.Done()
	return 0, ctx.Err()
}
func (waitingReader) Seek(int64, int) (int64, error)         { return 0, nil }
func (waitingReader) Close() error                           { return nil }
func (waitingReader) SetReadahead(int64)                     {}
func (waitingReader) SetReadaheadFunc(torrent.ReadaheadFunc) {}
func (waitingReader) SetResponsive()                         {}
func TestStreamReadCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	r := continuousSlidingReader{ctx: ctx, reader: waitingReader{}}
	done := make(chan error, 1)
	go func() { _, err := r.Read(make([]byte, 32)); done <- err }()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("read survived request cancellation")
	}
}
func TestStaleReleaseCannotWipeNewSession(t *testing.T) {
	e := Engine{currentSession: "new"}
	w := httptest.NewRecorder()
	e.handleRelease(w, httptest.NewRequest("POST", "/release?session=old", nil))
	if !strings.Contains(w.Body.String(), "stale_session") || e.currentSession != "new" {
		t.Fatal(w.Body.String())
	}
}

var _ io.Reader = waitingReader{}
