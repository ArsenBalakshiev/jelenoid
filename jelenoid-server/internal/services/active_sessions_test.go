package services

import (
	"sync"
	"sync/atomic"
	"testing"
)

func newTestActiveSessionsService(limit, queueLimit int) *ActiveSessionsService {
	return NewActiveSessionsService(
		limit,
		queueLimit,
		600000,
		30000,
		10,
		100,
		nil,
		make(chan struct{}, 1),
		true,
	)
}

func TestTryReserveSlot_NoOverReservation(t *testing.T) {
	const limit = 10
	const goroutines = 1000

	s := newTestActiveSessionsService(limit, 100)

	var wg sync.WaitGroup
	var reserved atomic.Int32
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if s.TryReserveSlot() {
				reserved.Add(1)
			}
		}()
	}
	wg.Wait()

	got := reserved.Load()
	if got > int32(limit) {
		t.Fatalf("over-reservation: reserved=%d, limit=%d", got, limit)
	}
	if got != int32(limit) {
		t.Fatalf("expected full reservation under burst, got=%d, limit=%d", got, limit)
	}
	if inProg := s.GetInProgressCount(); inProg != limit {
		t.Fatalf("in-progress counter mismatch: got=%d, want=%d", inProg, limit)
	}
}

func TestTryReserveSlot_BlocksWhenFull(t *testing.T) {
	const limit = 3

	s := newTestActiveSessionsService(limit, 100)
	for i := 0; i < limit; i++ {
		if !s.TryReserveSlot() {
			t.Fatalf("slot %d should be reservable", i)
		}
	}
	if s.TryReserveSlot() {
		t.Fatal("expected reservation to fail when full")
	}

	s.ReleaseSlot()
	if !s.TryReserveSlot() {
		t.Fatal("expected reservation to succeed after release")
	}
}

func TestReleaseSlot_DoesNotGoNegative(t *testing.T) {
	s := newTestActiveSessionsService(5, 100)

	s.ReleaseSlot()
	s.ReleaseSlot()

	if got := s.GetInProgressCount(); got != -2 {
		t.Fatalf("expected -2 after extra releases, got=%d", got)
	}
}
