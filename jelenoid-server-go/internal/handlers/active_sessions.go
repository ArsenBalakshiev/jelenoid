package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

type ActiveSessionsHandler struct {
	activeSessions *services.ActiveSessionsService
}

func NewActiveSessionsHandler(activeSessions *services.ActiveSessionsService) *ActiveSessionsHandler {
	return &ActiveSessionsHandler{activeSessions: activeSessions}
}

func (h *ActiveSessionsHandler) GetAllSessions(w http.ResponseWriter, r *http.Request) {
	sessions := h.activeSessions.GetSeleniumActiveSessions()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(sessions)
}

func (h *ActiveSessionsHandler) GetAllPendingRequests(w http.ResponseWriter, r *http.Request) {
	requests := h.activeSessions.GetSeleniumPendingRequests()
	var result []dto.PendingRequest
	for _, req := range requests {
		result = append(result, *req)
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

func (h *ActiveSessionsHandler) GetAllSessionsSize(w http.ResponseWriter, r *http.Request) {
	count := h.activeSessions.GetSeleniumSessionLimit()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(count)
}

func (h *ActiveSessionsHandler) GetAllPendingRequestsSize(w http.ResponseWriter, r *http.Request) {
	count := h.activeSessions.GetQueueSize()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(count)
}