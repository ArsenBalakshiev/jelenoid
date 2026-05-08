package handlers

import (
	"log"
	"net"
	"net/http"
	"sync"
	"time"

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
	log.Printf("VNC: Request received: %s %s", r.Method, r.URL.Path)
	parts := SplitPath(r.URL.Path)
	if len(parts) < 2 || parts[0] != "vnc" {
		log.Printf("VNC: Invalid path, parts=%v", parts)
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	sessionID := parts[1]
	log.Printf("VNC: Session ID: %s", sessionID)

	session := h.activeSessions.Get(sessionID)
	if session == nil {
		log.Printf("VNC: Session %s not found.", sessionID)
		http.Error(w, "Session not found", http.StatusNotFound)
		return
	}

	log.Printf("VNC: Session found, container: %s", session.ContainerInfo.ContainerName)

	clientConn, err := vncUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("VNC: Failed to upgrade: %v", err)
		return
	}
	defer clientConn.Close()

	containerInfo := session.ContainerInfo
	if containerInfo == nil {
		log.Printf("VNC: Container info missing")
		http.Error(w, "Container info missing", http.StatusInternalServerError)
		return
	}

	vncConn, err := net.DialTimeout("tcp", containerInfo.ContainerName+":5900", 5*time.Second)
	if err != nil {
		log.Printf("VNC Proxy: Failed to connect to VNC socket in container %s: %v", containerInfo.ContainerName, err)
		clientConn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "VNC connection failed"))
		return
	}
	defer vncConn.Close()

	log.Printf("VNC Proxy: TCP socket connected to %s:5900 for client %s", containerInfo.ContainerName, clientConn.RemoteAddr().String())

	var wg sync.WaitGroup
	wg.Add(2)

	var mu sync.Mutex
	vncClosed := false
	wsClosed := false

	go func() {
		defer wg.Done()
		buf := make([]byte, 8192)
		for {
			vncConn.SetReadDeadline(time.Now().Add(30 * time.Second))
			n, err := vncConn.Read(buf)
			if err != nil {
				mu.Lock()
				if !vncClosed {
					vncClosed = true
				}
				mu.Unlock()
				break
			}
			if err := clientConn.WriteMessage(websocket.BinaryMessage, buf[:n]); err != nil {
				mu.Lock()
				if !wsClosed {
					wsClosed = true
				}
				mu.Unlock()
				break
			}
		}
	}()

	go func() {
		defer wg.Done()
		for {
			clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
			messageType, message, err := clientConn.ReadMessage()
			if err != nil {
				mu.Lock()
				if !wsClosed {
					wsClosed = true
				}
				mu.Unlock()
				break
			}
			if messageType == websocket.BinaryMessage {
				_, err := vncConn.Write(message)
				if err != nil {
					mu.Lock()
					if !vncClosed {
						vncClosed = true
					}
					mu.Unlock()
					break
				}
			}
		}
	}()

	wg.Wait()
	log.Printf("VNC Proxy: Connection closed for session %s", sessionID)
}