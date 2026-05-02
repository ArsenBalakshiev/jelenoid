package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

type BrowserManagerHandler struct {
	browserManager *services.BrowserManagerService
}

func NewBrowserManagerHandler(browserManager *services.BrowserManagerService) *BrowserManagerHandler {
	return &BrowserManagerHandler{browserManager: browserManager}
}

func (h *BrowserManagerHandler) GetBrowsers(w http.ResponseWriter, r *http.Request) {
	browsers := h.browserManager.GetAllBrowsers()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(browsers)
}

func (h *BrowserManagerHandler) AddBrowser(w http.ResponseWriter, r *http.Request) {
	var browserInfo dto.BrowserInfo
	if err := json.NewDecoder(r.Body).Decode(&browserInfo); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	result := h.browserManager.AddBrowser(&browserInfo)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

func (h *BrowserManagerHandler) DeleteBrowser(w http.ResponseWriter, r *http.Request) {
	browserName := r.URL.Query().Get("browserName")
	browserVersion := r.URL.Query().Get("browserVersion")
	if browserName == "" || browserVersion == "" {
		http.Error(w, "browserName and browserVersion query parameters required", http.StatusBadRequest)
		return
	}
	result := h.browserManager.DeleteBrowser(browserName, browserVersion)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}