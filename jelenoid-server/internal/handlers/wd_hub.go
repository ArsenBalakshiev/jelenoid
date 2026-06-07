package handlers

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

type WdHubHandler struct {
	seleniumService *services.SeleniumSessionService
	activeSessions  *services.ActiveSessionsService
}

func NewWdHubHandler(seleniumService *services.SeleniumSessionService, activeSessions *services.ActiveSessionsService) *WdHubHandler {
	return &WdHubHandler{
		seleniumService: seleniumService,
		activeSessions:  activeSessions,
	}
}

func (h *WdHubHandler) CreateSession(w http.ResponseWriter, r *http.Request) {
	var requestBody map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&requestBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	result, err := h.seleniumService.CreateSessionOrQueue(requestBody)
	if err != nil {
		if httpErr, ok := err.(*services.HTTPError); ok {
			WriteErrorJSON(w, httpErr.StatusCode, httpErr.Message)
			return
		}
		WriteErrorJSON(w, http.StatusInternalServerError, err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

func (h *WdHubHandler) DeleteSession(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}
	h.seleniumService.DeleteSession(sessionID)
	w.WriteHeader(http.StatusOK)
}

func (h *WdHubHandler) ProxyRequest(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}
	proxy := h.activeSessions.GetProxy(sessionID)
	if proxy == nil {
		http.Error(w, "Session not found: "+sessionID, http.StatusNotFound)
		return
	}
	proxy.ServeHTTP(w, r)
}

func (h *WdHubHandler) UploadFile(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}

	var payload map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	base64EncodedZip, ok := payload["file"].(string)
	if !ok || base64EncodedZip == "" {
		http.Error(w, "Missing file data", http.StatusBadRequest)
		return
	}

	fileBytes, err := base64.StdEncoding.DecodeString(base64EncodedZip)
	if err != nil {
		http.Error(w, "Invalid base64 data", http.StatusBadRequest)
		return
	}

	filePath, err := h.seleniumService.UploadFileToSession(sessionID, fileBytes)
	if err != nil {
		if httpErr, ok := err.(*services.HTTPError); ok {
			http.Error(w, httpErr.Message, httpErr.StatusCode)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"value": filePath})
}

func ExtractSessionID(path string) string {
	const marker = "/session/"
	idx := strings.Index(path, marker)
	if idx < 0 {
		return ""
	}
	rest := path[idx+len(marker):]
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		return rest[:i]
	}
	return rest
}

func WriteErrorJSON(w http.ResponseWriter, statusCode int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"value": map[string]interface{}{
			"error":      "session not created",
			"message":    message,
			"stacktrace": "",
		},
	})
}
