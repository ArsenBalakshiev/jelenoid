package handlers

import (
	"log"
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
	log.Printf("EVENTS: Request received from %s", r.RemoteAddr)
	h.hub.ServeHTTP(w, r)
}