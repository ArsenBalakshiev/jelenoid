package services

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"
)

type SSEEvent struct {
	Event string
	Data  interface{}
}

type SSEHub struct {
	subscribers         map[chan []byte]bool
	mu                 sync.RWMutex
	initialDataBuilder func() SSEEvent
}

func NewSSEHub() *SSEHub {
	return &SSEHub{
		subscribers: make(map[chan []byte]bool),
	}
}

func (h *SSEHub) SetInitialDataBuilder(builder func() SSEEvent) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.initialDataBuilder = builder
}

func (h *SSEHub) Subscribe() chan []byte {
	ch := make(chan []byte, 16)
	h.mu.Lock()
	h.subscribers[ch] = true
	h.mu.Unlock()
	return ch
}

func (h *SSEHub) Unsubscribe(ch chan []byte) {
	h.mu.Lock()
	delete(h.subscribers, ch)
	close(ch)
	h.mu.Unlock()
}

func (h *SSEHub) Broadcast(event SSEEvent) {
	data, err := json.Marshal(event.Data)
	if err != nil {
		return
	}
	frame := fmt.Appendf(nil, "event: %s\ndata: %s\n\n", event.Event, string(data))

	h.mu.RLock()
	defer h.mu.RUnlock()
	for ch := range h.subscribers {
		select {
		case ch <- frame:
		default:
		}
	}
}

func (h *SSEHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log.Printf("SSEHub: Serving /events from %s", r.RemoteAddr)

	origin := r.Header.Get("Origin")
	if origin != "" {
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Access-Control-Allow-Credentials", "true")
		w.Header().Set("Access-Control-Expose-Headers", "Content-Type, Cache-Control, Connection, X-Accel-Buffering")
	}

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

	h.mu.RLock()
	initialBuilder := h.initialDataBuilder
	h.mu.RUnlock()

	if initialBuilder != nil {
		initialEvent := initialBuilder()
		data, err := json.Marshal(initialEvent.Data)
		if err == nil {
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", initialEvent.Event, string(data))
			flusher.Flush()
		}
	}

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case frame := <-ch:
			w.Write(frame)
			flusher.Flush()
		}
	}
}