package handlers

import (
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

type EventsHandler struct {
	hub *services.SSEHub
}

func NewEventsHandler(hub *services.SSEHub) *EventsHandler {
	return &EventsHandler{hub: hub}
}

func (h *EventsHandler) Subscribe(w http.ResponseWriter, r *http.Request) {
	h.hub.ServeHTTP(w, r)
}