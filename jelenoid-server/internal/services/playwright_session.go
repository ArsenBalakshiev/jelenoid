package services

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

type PlaywrightSessionService struct {
	activeSessions   *ActiveSessionsService
	dockerService    *DockerExternalService
	browserManager   *BrowserManagerService
	statusChan       chan struct{}
	sessionTimeoutMs int64

	wsUpgrader websocket.Upgrader
}

func NewPlaywrightSessionService(
	activeSessions *ActiveSessionsService,
	dockerService *DockerExternalService,
	browserManager *BrowserManagerService,
	statusChan chan struct{},
	sessionTimeoutMs int64,
) *PlaywrightSessionService {
	return &PlaywrightSessionService{
		activeSessions:   activeSessions,
		dockerService:    dockerService,
		browserManager:   browserManager,
		statusChan:       statusChan,
		sessionTimeoutMs: sessionTimeoutMs,
		wsUpgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

func (s *PlaywrightSessionService) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	conn, err := s.wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	playwrightVersion := s.getPlaywrightVersion(r.URL.Path)

	copyHeaders := make(http.Header)
	for key, values := range r.Header {
		lower := strings.ToLower(key)
		if lower == "upgrade" || lower == "connection" || strings.HasPrefix(lower, "sec-websocket-") {
			continue
		}
		if len(values) > 0 {
			copyHeaders.Set(key, values[0])
		}
	}

	pair := &PlaywrightSessionPair{
		ClientConn:     conn,
		RequestHeaders: copyHeaders,
		Version:        playwrightVersion,
	}
	s.activeSessions.PutPlaywrightActiveSession(conn, pair)

	if s.activeSessions.TryAcquirePlaywrightSlot() {
		go s.startProxyForSession(conn, pair, playwrightVersion)
	} else {
		if !s.activeSessions.IsQueueEnabled() {
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "No free slots. Queue is disabled."))
			conn.Close()
			s.activeSessions.RemovePlaywrightActiveSession(conn)
			return
		}
		enqueued := s.activeSessions.OfferPlaywrightQueue(pair)
		if !enqueued {
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "Proxy queue is full."))
			conn.Close()
			s.activeSessions.RemovePlaywrightActiveSession(conn)
		}
	}
}

func (s *PlaywrightSessionService) getPlaywrightVersion(path string) string {
	// Handle /playwright-1.58.0 or /playwright/1.58.0
	if strings.HasPrefix(path, "/playwright-") {
		return strings.TrimPrefix(path, "/playwright-")
	}
	if strings.HasPrefix(path, "/playwright/") {
		return strings.TrimPrefix(path, "/playwright/")
	}
	return ""
}

func (s *PlaywrightSessionService) startProxyForSession(clientConn *websocket.Conn, pair *PlaywrightSessionPair, playwrightVersion string) {
	defer func() {
		if r := recover(); r != nil {
			s.activeSessions.RemovePlaywrightActiveSession(clientConn)
			s.activeSessions.ReleasePlaywrightSlot()
			clientConn.Close()
			s.dispatchStatusUpdate()
		}
	}()

	browserInfo := s.browserManager.GetBrowserInfoByBrowserNameAndVersion("playwright", playwrightVersion)
	if browserInfo == nil {
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		clientConn.Close()
		s.dispatchStatusUpdate()
		return
	}

	containerInfo, err := s.dockerService.StartPlaywrightContainer(browserInfo.DockerImageName, browserInfo.Version)
	if err != nil {
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		clientConn.Close()
		s.dispatchStatusUpdate()
		return
	}

	pair.Lock.Lock()
	pair.ContainerInfo = containerInfo
	pair.Version = playwrightVersion
	pair.Lock.Unlock()

	containerURL := fmt.Sprintf("ws://%s:3000/", containerInfo.ContainerName)

	headers := pair.RequestHeaders
	if headers == nil {
		headers = make(http.Header)
	}

	containerConn, _, err := websocket.DefaultDialer.Dial(containerURL, headers)
	if err != nil {
		clientConn.Close()
		go s.dockerService.StopContainer(containerInfo.ContainerID)
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		s.dispatchStatusUpdate()
		return
	}

	pair.Lock.Lock()
	pair.ContainerConn = containerConn
	pair.ConnectionEstablished.Store(true)
	for _, msg := range pair.PendingMessages {
		containerConn.WriteMessage(websocket.TextMessage, msg)
	}
	pair.PendingMessages = nil
	pair.Lock.Unlock()

	s.dispatchStatusUpdate()

	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			messageType, message, err := containerConn.ReadMessage()
			if err != nil {
				return
			}
			ci := pair.ContainerInfo
			if ci != nil {
				ci.UpdateActivity()
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

		ci := pair.ContainerInfo
		if ci != nil {
			ci.UpdateActivity()
		}
		if pair.ConnectionEstablished.Load() {
			containerConn.WriteMessage(messageType, message)
		} else {
			pair.Lock.Lock()
			if pair.ConnectionEstablished.Load() {
				pair.Lock.Unlock()
				containerConn.WriteMessage(messageType, message)
			} else {
				pair.PendingMessages = append(pair.PendingMessages, message)
				pair.Lock.Unlock()
			}
		}
	}

	containerConn.WriteMessage(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	containerConn.Close()
	<-done

	s.dispatchStatusUpdate()

	s.handleDisconnect(clientConn, pair)
}

func (s *PlaywrightSessionService) handleDisconnect(clientConn *websocket.Conn, removedPair *PlaywrightSessionPair) {
	if removedPair != nil {
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.cleanupContainerOnce(removedPair)

		nextPair := s.activeSessions.PollFromPlaywrightQueue()
		if nextPair != nil {
			go s.startProxyForSession(nextPair.ClientConn, nextPair, nextPair.Version)
		} else {
			s.activeSessions.ReleasePlaywrightSlot()
		}
	} else {
		s.activeSessions.RemoveFromPlaywrightQueue(clientConn)
	}
	s.dispatchStatusUpdate()
}

func (s *PlaywrightSessionService) cleanupContainerOnce(pair *PlaywrightSessionPair) {
	pair.Lock.Lock()
	defer pair.Lock.Unlock()
	if pair.ContainerInfo != nil {
		containerID := pair.ContainerInfo.ContainerID
		pair.ContainerInfo = nil
		go s.dockerService.StopContainer(containerID)
	}
}

func (s *PlaywrightSessionService) CheckSessionTimeouts() {
	now := time.Now().UnixMilli()
	sessions := s.activeSessions.GetPlaywrightActiveSessions()
	for conn, pair := range sessions {
		pair.Lock.Lock()
		if pair.ContainerInfo != nil && (now-pair.ContainerInfo.GetLastActivity()) > s.sessionTimeoutMs {
			pair.Lock.Unlock()
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "Session timeout"))
			conn.Close()
			s.dispatchStatusUpdate()
			continue
		}
		pair.Lock.Unlock()
	}
}

func (s *PlaywrightSessionService) Shutdown() {
	sessions := s.activeSessions.GetPlaywrightActiveSessions()
	for conn, pair := range sessions {
		conn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseGoingAway, "Server shutting down"))
		conn.Close()
		if pair.ContainerInfo != nil {
			go s.dockerService.StopContainer(pair.ContainerInfo.ContainerID)
			pair.Lock.Lock()
			pair.ContainerInfo = nil
			pair.Lock.Unlock()
		}
	}
	s.activeSessions.ClearPlaywrightWaitingQueue()
	s.dispatchStatusUpdate()
}

func (s *PlaywrightSessionService) dispatchStatusUpdate() {
	select {
	case s.statusChan <- struct{}{}:
	default:
	}
}