package handlers

import (
	"fmt"
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

type LogsHandler struct {
	seleniumService *services.SeleniumSessionService
}

func NewLogsHandler(seleniumService *services.SeleniumSessionService) *LogsHandler {
	return &LogsHandler{seleniumService: seleniumService}
}

func (h *LogsHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	const prefix = "/logs/"
	if len(r.URL.Path) <= len(prefix) {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	sessionID := r.URL.Path[len(prefix):]

	ch, err := h.seleniumService.StreamLogsForSession(r.Context(), sessionID)
	if err != nil {
		if httpErr, ok := err.(*services.HTTPError); ok {
			http.Error(w, httpErr.Message, httpErr.StatusCode)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case data, ok := <-ch:
			if !ok {
				return
			}
			fmt.Fprintf(w, "data: %s\n\n", data)
			flusher.Flush()
		}
	}
}