package handlers

import (
	"log"
	"net"
	"net/http"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
	"github.com/gorilla/websocket"
)

type VncProxyHandler struct {
	activeSessions *services.ActiveSessionsService
}

func NewVncProxyHandler(activeSessions *services.ActiveSessionsService) *VncProxyHandler {
	return &VncProxyHandler{activeSessions: activeSessions}
}

var vncUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
	Subprotocols: []string{"binary"},
}

func (h *VncProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	parts := SplitPath(r.URL.Path)
	if len(parts) < 2 || parts[0] != "vnc" {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	sessionID := parts[1]

	session := h.activeSessions.Get(sessionID)
	if session == nil {
		log.Printf("VNC: Session %s not found.", sessionID)
		http.Error(w, "Session not found", http.StatusNotFound)
		return
	}

	clientConn, err := vncUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("VNC: Failed to upgrade: %v", err)
		return
	}
	defer clientConn.Close()

	containerInfo := session.ContainerInfo
	if containerInfo == nil {
		http.Error(w, "Container info missing", http.StatusInternalServerError)
		return
	}

	vncConn, err := net.Dial("tcp", containerInfo.ContainerName+":5900")
	if err != nil {
		log.Printf("VNC Proxy: Failed to connect to VNC socket in container %s: %v", containerInfo.ContainerName, err)
		return
	}
	defer vncConn.Close()

	log.Printf("VNC Proxy: TCP socket connected to %s:5900 for client %s", containerInfo.ContainerName, clientConn.RemoteAddr().String())

	done := make(chan struct{})

	// Read from VNC and write to WebSocket
	go func() {
		buf := make([]byte, 8192)
		for {
			n, err := vncConn.Read(buf)
			if err != nil {
				close(done)
				return
			}
			if err := clientConn.WriteMessage(websocket.BinaryMessage, buf[:n]); err != nil {
				return
			}
		}
	}()

	// Read from WebSocket and write to VNC
	for {
		messageType, message, err := clientConn.ReadMessage()
		if err != nil {
			break
		}
		if messageType == websocket.BinaryMessage {
			if _, err := vncConn.Write(message); err != nil {
				break
			}
		}
	}
	close(done)
}