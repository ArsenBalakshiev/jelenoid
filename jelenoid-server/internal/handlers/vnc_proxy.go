package handlers

import (
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

var vncBufPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, 8192)
	},
}

func (h *VncProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	const prefix = "/vnc/"
	if len(r.URL.Path) <= len(prefix) {
		http.Error(w, "Invalid path", http.StatusBadRequest)
		return
	}
	sessionID := r.URL.Path[len(prefix):]

	session := h.activeSessions.Get(sessionID)
	if session == nil {
		http.Error(w, "Session not found", http.StatusNotFound)
		return
	}

	clientConn, err := vncUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer clientConn.Close()

	containerInfo := session.ContainerInfo
	if containerInfo == nil {
		clientConn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseInternalServerErr, "Container info missing"))
		return
	}

	vncConn, err := net.DialTimeout("tcp", containerInfo.ContainerName+":5900", 5*time.Second)
	if err != nil {
		clientConn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "VNC connection failed"))
		return
	}
	defer vncConn.Close()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		buf := vncBufPool.Get().([]byte)
		defer vncBufPool.Put(buf)
		for {
			vncConn.SetReadDeadline(time.Now().Add(30 * time.Second))
			n, err := vncConn.Read(buf)
			if err != nil {
				break
			}
			if err := clientConn.WriteMessage(websocket.BinaryMessage, buf[:n]); err != nil {
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
				break
			}
			if messageType == websocket.BinaryMessage {
				if _, err := vncConn.Write(message); err != nil {
					break
				}
			}
		}
	}()

	wg.Wait()
}