package services

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
)

type EmitterService struct {
	mu       sync.Mutex
	emitters map[chan struct{}]bool
}

func NewEmitterService() *EmitterService {
	return &EmitterService{
		emitters: make(map[chan struct{}]bool),
	}
}

func (s *EmitterService) AddEmitter(done chan struct{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.emitters[done] = true
}

func (s *EmitterService) RemoveEmitter(done chan struct{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.emitters, done)
}

func (s *EmitterService) Dispatch(event interface{}) {
	data, err := json.Marshal(event)
	if err != nil {
		log.Printf("Failed to marshal event: %v", err)
		return
	}
	// We dispatch via the StatusNotifier which has a channel
	// This is handled differently in Go - see status_notifier.go
	_ = data
}

type SSEEvent struct {
	Event string
	Data  interface{}
}

type SSEHub struct {
	subscribers map[chan SSEEvent]bool
	mu          sync.RWMutex
}

func NewSSEHub() *SSEHub {
	return &SSEHub{
		subscribers: make(map[chan SSEEvent]bool),
	}
}

func (h *SSEHub) Subscribe() chan SSEEvent {
	ch := make(chan SSEEvent, 16)
	h.mu.Lock()
	h.subscribers[ch] = true
	h.mu.Unlock()
	return ch
}

func (h *SSEHub) Unsubscribe(ch chan SSEEvent) {
	h.mu.Lock()
	delete(h.subscribers, ch)
	close(ch)
	h.mu.Unlock()
}

func (h *SSEHub) Broadcast(event SSEEvent) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for ch := range h.subscribers {
		select {
		case ch <- event:
		default:
		}
	}
}

func (h *SSEHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	ch := h.Subscribe()
	defer h.Unsubscribe(ch)

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case event := <-ch:
			data, err := json.Marshal(event.Data)
			if err != nil {
				continue
			}
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event.Event, string(data))
			flusher.Flush()
		}
	}
}