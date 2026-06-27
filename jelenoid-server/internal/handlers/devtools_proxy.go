package handlers

import (
	"fmt"
	"net/http"

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

	targetURL := fmt.Sprintf("ws://%s:7070/devtools/page/%s", session.ContainerInfo.ContainerName, session.RemoteSessionID)

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

	done := make(chan struct{})

	go func() {
		defer close(done)
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

	for {
		messageType, message, err := clientConn.ReadMessage()
		if err != nil {
			break
		}
		if err := containerConn.WriteMessage(messageType, message); err != nil {
			break
		}
	}
	containerConn.WriteMessage(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	<-done
}