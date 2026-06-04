package handlers

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

var copyBufPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, 32*1024)
	},
}

type WdHubHandler struct {
	seleniumService *services.SeleniumSessionService
}

func NewWdHubHandler(seleniumService *services.SeleniumSessionService) *WdHubHandler {
	return &WdHubHandler{seleniumService: seleniumService}
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
	sessionID := ExtractSessionID(r.URL.Path)
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}
	h.seleniumService.DeleteSession(sessionID)
	w.WriteHeader(http.StatusOK)
}

func (h *WdHubHandler) ProxyRequest(w http.ResponseWriter, r *http.Request) {
	sessionID := ExtractSessionID(r.URL.Path)
	if sessionID == "" {
		http.Error(w, "Session ID required", http.StatusBadRequest)
		return
	}

	idx := strings.Index(r.URL.Path, "/wd/hub")
	if idx < 0 {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	relativePath := r.URL.Path[idx+len("/wd/hub"):]

	var body io.Reader
	var contentLength int64
	if r.Method != "GET" && r.Body != nil {
		body = r.Body
		contentLength = r.ContentLength
	}

	resp, err := h.seleniumService.ProxyRequest(sessionID, r.Method, relativePath, r.Header, body, contentLength)
	if err != nil {
		if httpErr, ok := err.(*services.HTTPError); ok {
			WriteErrorJSON(w, httpErr.StatusCode, httpErr.Message)
		} else {
			WriteErrorJSON(w, http.StatusBadGateway, err.Error())
		}
		return
	}
	defer resp.Body.Close()

	dst := w.Header()
	for key, values := range resp.Header {
		switch key {
		case "Content-Length", "Transfer-Encoding", "Host", "Connection", "Upgrade":
			continue
		}
		dst[key] = values
	}
	w.WriteHeader(resp.StatusCode)
	buf := copyBufPool.Get().([]byte)
	io.CopyBuffer(w, resp.Body, buf)
	copyBufPool.Put(buf)
}

func (h *WdHubHandler) UploadFile(w http.ResponseWriter, r *http.Request) {
	sessionID := ExtractSessionID(r.URL.Path)
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

