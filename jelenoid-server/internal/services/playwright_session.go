package services

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/gorilla/websocket"
)

type PlaywrightSessionService struct {
	activeSessions   *ActiveSessionsService
	dockerService    *DockerExternalService
	browserManager   *BrowserManagerService
	pool             *PlaywrightContainerPool
	statusChan       chan struct{}
	sessionTimeoutMs int64

	wsUpgrader websocket.Upgrader
}

func NewPlaywrightSessionService(
	activeSessions *ActiveSessionsService,
	dockerService *DockerExternalService,
	browserManager *BrowserManagerService,
	pool *PlaywrightContainerPool,
	statusChan chan struct{},
	sessionTimeoutMs int64,
) *PlaywrightSessionService {
	return &PlaywrightSessionService{
		activeSessions:   activeSessions,
		dockerService:    dockerService,
		browserManager:   browserManager,
		pool:             pool,
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

	containerInfo, poolEntry, err := s.acquireContainer(browserInfo.DockerImageName, browserInfo.Version)
	if err != nil {
		code := websocket.CloseInternalServerErr
		reason := "Failed to acquire container"
		if errors.Is(err, ErrPoolExhausted) {
			code = websocket.CloseTryAgainLater
			reason = "Playwright container pool is exhausted"
		}
		clientConn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(code, reason))
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		clientConn.Close()
		s.dispatchStatusUpdate()
		return
	}

	pair.Lock.Lock()
	pair.Version = playwrightVersion
	pair.PoolEntry = poolEntry
	if poolEntry == nil {
		pair.ContainerInfo = containerInfo
	}
	pair.Lock.Unlock()

	if poolEntry != nil {
		if !s.waitForContainerReady(clientConn, pair, poolEntry) {
			return
		}
		containerInfo = pair.ContainerInfo
	}

	containerURL := fmt.Sprintf("ws://%s:3000/", containerInfo.ContainerName)

	headers := pair.RequestHeaders
	if headers == nil {
		headers = make(http.Header)
	}

	containerConn, _, err := websocket.DefaultDialer.Dial(containerURL, headers)
	if err != nil {
		clientConn.Close()
		if poolEntry != nil {
			s.releaseContainer(poolEntry)
		} else if containerInfo != nil {
			go s.dockerService.StopContainer(containerInfo.ContainerID)
		}
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

func (s *PlaywrightSessionService) acquireContainer(image, version string) (*dto.ContainerInfo, *poolEntry, error) {
	if s.pool != nil && s.pool.Enabled() {
		entry, err := s.pool.Acquire(image, version)
		if err != nil {
			return nil, nil, err
		}
		return entry.containerInfo, entry, nil
	}

	info, err := s.dockerService.StartPlaywrightContainer(image, version)
	if err != nil {
		return nil, nil, err
	}
	return info, nil, nil
}

func (s *PlaywrightSessionService) waitForContainerReady(clientConn *websocket.Conn, pair *PlaywrightSessionPair, entry *poolEntry) bool {
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if entry.State() == poolStateReady && entry.containerInfo != nil {
			pair.Lock.Lock()
			pair.ContainerInfo = entry.containerInfo
			pair.Lock.Unlock()
			return true
		}
		if entry.State() == poolStateStopped {
			s.activeSessions.RemovePlaywrightActiveSession(clientConn)
			s.activeSessions.ReleasePlaywrightSlot()
			clientConn.Close()
			s.dispatchStatusUpdate()
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}

	s.activeSessions.RemovePlaywrightActiveSession(clientConn)
	s.activeSessions.ReleasePlaywrightSlot()
	clientConn.Close()
	s.dispatchStatusUpdate()
	return false
}

func (s *PlaywrightSessionService) releaseContainer(entry *poolEntry) {
	if entry != nil && s.pool != nil && s.pool.Enabled() {
		s.pool.Release(entry)
	}
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
	entry := pair.PoolEntry
	containerInfo := pair.ContainerInfo
	pair.PoolEntry = nil
	pair.ContainerInfo = nil
	pair.Lock.Unlock()

	if entry != nil {
		s.releaseContainer(entry)
	} else if containerInfo != nil {
		go s.dockerService.StopContainer(containerInfo.ContainerID)
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
		pair.Lock.Lock()
		entry := pair.PoolEntry
		containerInfo := pair.ContainerInfo
		pair.PoolEntry = nil
		pair.ContainerInfo = nil
		pair.Lock.Unlock()

		if entry != nil {
			s.releaseContainer(entry)
		} else if containerInfo != nil {
			go s.dockerService.StopContainer(containerInfo.ContainerID)
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
