package handlers

import (
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/services"
	"github.com/gorilla/websocket"
)

const (
	vncWsReadDeadline  = 90 * time.Second
	vncWsPingInterval  = 30 * time.Second
	vncWsWriteTimeout  = 10 * time.Second
)

type VncProxyHandler struct {
	activeSessions *services.ActiveSessionsService
	upgrader       websocket.Upgrader
}

func NewVncProxyHandler(activeSessions *services.ActiveSessionsService) *VncProxyHandler {
	return &VncProxyHandler{
		activeSessions: activeSessions,
		upgrader: websocket.Upgrader{
			CheckOrigin:  func(r *http.Request) bool { return true },
			Subprotocols: []string{"binary"},
		},
	}
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

	clientConn, err := h.upgrader.Upgrade(w, r, nil)
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

	var closeOnce sync.Once
	closeBoth := func() {
		closeOnce.Do(func() {
			clientConn.Close()
			vncConn.Close()
		})
	}

	clientConn.SetReadDeadline(time.Now().Add(vncWsReadDeadline))
	clientConn.SetPongHandler(func(string) error {
		clientConn.SetReadDeadline(time.Now().Add(vncWsReadDeadline))
		return nil
	})

	done := make(chan struct{}, 2)

	go func() {
		defer func() {
			closeBoth()
			done <- struct{}{}
		}()
		buf := vncBufPool.Get().([]byte)
		defer vncBufPool.Put(buf)
		for {
			n, err := vncConn.Read(buf)
			if err != nil {
				return
			}
			if err := clientConn.WriteMessage(websocket.BinaryMessage, buf[:n]); err != nil {
				return
			}
		}
	}()

	go func() {
		defer func() {
			closeBoth()
			done <- struct{}{}
		}()
		pingTick := time.NewTicker(vncWsPingInterval)
		defer pingTick.Stop()
		for {
			clientConn.SetReadDeadline(time.Now().Add(vncWsReadDeadline))
			messageType, message, err := clientConn.ReadMessage()
			if err != nil {
				return
			}
			if messageType == websocket.BinaryMessage {
				if _, err := vncConn.Write(message); err != nil {
					return
				}
			}
			select {
			case <-pingTick.C:
				_ = clientConn.WriteControl(websocket.PingMessage, nil, time.Now().Add(vncWsWriteTimeout))
			default:
			}
		}
	}()

	<-done
}
