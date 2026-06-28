package handlers

import (
	"fmt"
	"net/http"
	"sync"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
	"github.com/gorilla/websocket"
)

type DevToolsProxyHandler struct {
	activeSessions *services.ActiveSessionsService
	upgrader       websocket.Upgrader
}

func NewDevToolsProxyHandler(activeSessions *services.ActiveSessionsService) *DevToolsProxyHandler {
	return &DevToolsProxyHandler{
		activeSessions: activeSessions,
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

func (h *DevToolsProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	sessionID := ExtractSessionID(r.URL.Path)
	if sessionID == "" {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}

	session := h.activeSessions.Get(sessionID)
	if session == nil {
		http.Error(w, "Session not found", http.StatusNotFound)
		return
	}

	if session.ContainerInfo == nil {
		http.Error(w, "Container for session not found", http.StatusNotFound)
		return
	}

	if session.DebuggerAddress == "" {
		http.Error(w, "Debugger address not available for session", http.StatusNotFound)
		return
	}

	targetURL := fmt.Sprintf("ws://%s:7070/devtools/page/%s?debuggerAddress=%s",
		session.ContainerInfo.ContainerName,
		session.RemoteSessionID,
		session.DebuggerAddress,
	)

	clientConn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer clientConn.Close()

	containerConn, _, err := websocket.DefaultDialer.Dial(targetURL, nil)
	if err != nil {
		return
	}
	defer containerConn.Close()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		for {
			messageType, message, err := containerConn.ReadMessage()
			if err != nil {
				return
			}
			if err := clientConn.WriteMessage(messageType, message); err != nil {
				return
			}
		}
	}()

	go func() {
		defer wg.Done()
		for {
			messageType, message, err := clientConn.ReadMessage()
			if err != nil {
				return
			}
			if err := containerConn.WriteMessage(messageType, message); err != nil {
				return
			}
		}
	}()

	wg.Wait()
}
