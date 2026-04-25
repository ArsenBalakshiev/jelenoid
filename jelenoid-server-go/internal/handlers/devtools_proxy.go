package handlers

import (
	"fmt"
	"log"
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
	"github.com/gorilla/websocket"
)

type DevToolsProxyHandler struct {
	activeSessions *services.ActiveSessionsService
}

func NewDevToolsProxyHandler(activeSessions *services.ActiveSessionsService) *DevToolsProxyHandler {
	return &DevToolsProxyHandler{activeSessions: activeSessions}
}

var devtoolsUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (h *DevToolsProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	parts := SplitPath(r.URL.Path)
	if len(parts) < 4 || parts[0] != "session" {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	sessionID := parts[1]

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
	log.Printf("CDP Proxy: Attempting to upgrade request for session %s to WebSocket, proxying to %s", sessionID, targetURL)

	clientConn, err := devtoolsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("CDP Proxy: Failed to upgrade: %v", err)
		return
	}
	defer clientConn.Close()

	containerConn, _, err := websocket.DefaultDialer.Dial(targetURL, nil)
	if err != nil {
		log.Printf("CDP Proxy: Failed to connect to target: %v", err)
		return
	}
	defer containerConn.Close()

	log.Printf("CDP Proxy: Successfully connected to target WebSocket: %s", targetURL)

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