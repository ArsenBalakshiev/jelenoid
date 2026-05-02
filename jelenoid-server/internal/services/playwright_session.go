package services

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

type PlaywrightSessionService struct {
	activeSessions   *ActiveSessionsService
	dockerService    *DockerExternalService
	browserManager   *BrowserManagerService
	sessionPublisher *SessionPublisher
	statusChan       chan struct{}
	sessionTimeoutMs int64
	authToken        string

	httpClient *http.Client
	wsUpgrader websocket.Upgrader
}

func NewPlaywrightSessionService(
	activeSessions *ActiveSessionsService,
	dockerService *DockerExternalService,
	browserManager *BrowserManagerService,
	sessionPublisher *SessionPublisher,
	statusChan chan struct{},
	sessionTimeoutMs int64,
	authToken string,
) *PlaywrightSessionService {
	return &PlaywrightSessionService{
		activeSessions:   activeSessions,
		dockerService:    dockerService,
		browserManager:   browserManager,
		sessionPublisher: sessionPublisher,
		statusChan:       statusChan,
		sessionTimeoutMs: sessionTimeoutMs,
		authToken:        authToken,
		httpClient: &http.Client{Timeout: 30 * time.Second},
		wsUpgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

func (s *PlaywrightSessionService) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log.Printf("Playwright WS: Request received: %s %s", r.Method, r.URL.Path)

	conn, err := s.wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("Playwright WS: Failed to upgrade: %v", err)
		return
	}

	if s.authToken != "" {
		token := r.Header.Get("X-Jelenoid-Token")
		if token != s.authToken {
			log.Printf("Session %s: Unauthorized Playwright connection attempt.", conn.RemoteAddr().String())
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.ClosePolicyViolation, "Unauthorized"))
			conn.Close()
			return
		}
	}

	playwrightVersion := s.getPlaywrightVersion(r.URL.Path)
	log.Printf("Playwright WS: Extracted version: '%s' from path: %s", playwrightVersion, r.URL.Path)

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
	}
	s.activeSessions.PutPlaywrightActiveSession(conn, pair)
	log.Printf("Session %s: Connection received.", conn.RemoteAddr().String())

	if s.activeSessions.TryAcquirePlaywrightSlot() {
		log.Printf("Session %s: Slot acquired immediately. Starting proxy.", conn.RemoteAddr().String())
		go s.startProxyForSession(conn, pair, playwrightVersion)
	} else {
		log.Printf("Session %s: No available slots. Attempting to queue.", conn.RemoteAddr().String())
		enqueued := s.activeSessions.OfferPlaywrightQueue(pair)
		if !enqueued {
			log.Printf("Session %s: Proxy queue is full. Rejecting connection.", conn.RemoteAddr().String())
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "Proxy queue is full."))
			conn.Close()
		} else {
			log.Printf("Session %s: Successfully queued. Waiting for a free slot.", conn.RemoteAddr().String())
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
			log.Printf("Session %s: Panic in startProxyForSession: %v", clientConn.RemoteAddr().String(), r)
			s.activeSessions.RemovePlaywrightActiveSession(clientConn)
			s.activeSessions.ReleasePlaywrightSlot()
			clientConn.Close()
			s.dispatchStatusUpdate()
		}
	}()

	browserInfo := s.browserManager.GetBrowserInfoByBrowserNameAndVersion("playwright", playwrightVersion)
	if browserInfo == nil {
		log.Printf("Session %s: No image found for playwright version %s", clientConn.RemoteAddr().String(), playwrightVersion)
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		clientConn.Close()
		s.dispatchStatusUpdate()
		return
	}

	containerInfo, err := s.dockerService.StartPlaywrightContainer(browserInfo.DockerImageName, browserInfo.Version)
	if err != nil {
		log.Printf("Session %s: Failed to start container: %v", clientConn.RemoteAddr().String(), err)
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

	log.Printf("Session %s: Started new container %s at address %s",
		clientConn.RemoteAddr().String(), containerInfo.ContainerID, containerInfo.ContainerName)

	containerURL := fmt.Sprintf("ws://%s:3000/", containerInfo.ContainerName)

	headers := pair.RequestHeaders
	if headers == nil {
		headers = make(http.Header)
	}

	containerConn, _, err := websocket.DefaultDialer.Dial(containerURL, headers)
	if err != nil {
		log.Printf("Session %s: Could not connect to container within timeout: %v", clientConn.RemoteAddr().String(), err)
		clientConn.Close()
		go s.dockerService.StopContainer(containerInfo.ContainerID)
		s.activeSessions.RemovePlaywrightActiveSession(clientConn)
		s.activeSessions.ReleasePlaywrightSlot()
		s.dispatchStatusUpdate()
		return
	}

	pair.Lock.Lock()
	pair.ContainerConn = containerConn
	pair.ConnectionEstablished = true
	for _, msg := range pair.PendingMessages {
		containerConn.WriteMessage(websocket.TextMessage, msg)
	}
	pair.PendingMessages = nil
	pair.Lock.Unlock()

	sessionInfo := s.sessionPublisher.CreateSessionAndPublish("playwright", playwrightVersion)
	pair.SessionInfo = sessionInfo

	s.dispatchStatusUpdate()

	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			messageType, message, err := containerConn.ReadMessage()
			if err != nil {
				if websocket.IsUnexpectedCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
					log.Printf("Session %s: Error reading from container: %v", clientConn.RemoteAddr().String(), err)
				}
				return
			}
			if pair.ContainerInfo != nil {
				pair.ContainerInfo.UpdateActivity()
			}
			if err := clientConn.WriteMessage(messageType, message); err != nil {
				log.Printf("Session %s: Failed to send message to client: %v", clientConn.RemoteAddr().String(), err)
				return
			}
		}
	}()

	for {
		messageType, message, err := clientConn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				log.Printf("Session %s: Client connection error: %v", clientConn.RemoteAddr().String(), err)
			}
			break
		}

		pair.Lock.Lock()
		if pair.ContainerInfo != nil {
			pair.ContainerInfo.UpdateActivity()
		}
		if pair.ConnectionEstablished {
			containerConn.WriteMessage(messageType, message)
		} else {
			pair.PendingMessages = append(pair.PendingMessages, message)
		}
		pair.Lock.Unlock()
	}

	containerConn.WriteMessage(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	containerConn.Close()
	<-done

	s.sessionPublisher.EndSessionByRemoteAndPublish(pair.SessionInfo)
	s.dispatchStatusUpdate()

	s.handleDisconnect(clientConn, pair)
}

func (s *PlaywrightSessionService) handleDisconnect(clientConn *websocket.Conn, removedPair *PlaywrightSessionPair) {
	log.Printf("Session %s: Client connection closed.", clientConn.RemoteAddr().String())

	if removedPair != nil {
		s.cleanupContainerOnce(removedPair)

		nextPair := s.activeSessions.PollFromPlaywrightQueue()
		if nextPair != nil {
			log.Printf("Session: Slot is being passed to queued session.")
			go s.startProxyForSession(nextPair.ClientConn, nextPair, "")
		} else {
			s.activeSessions.ReleasePlaywrightSlot()
			log.Printf("Slot released. Queue is empty. Available slots: %d.", s.activeSessions.AvailablePlaywrightSlots())
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
		log.Printf("Session: Cleaning up dedicated container %s.", containerID)
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
			log.Printf("Session %s timed out due to inactivity. Closing connection.", conn.RemoteAddr().String())
			pair.Lock.Unlock()
			conn.WriteMessage(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.CloseTryAgainLater, "Session timeout"))
			conn.Close()
			s.sessionPublisher.EndInactiveSessionAndPublish(pair.SessionInfo)
			s.dispatchStatusUpdate()
			continue
		}
		pair.Lock.Unlock()
	}
}

func (s *PlaywrightSessionService) Shutdown() {
	log.Println("Shutting down PlaywrightSessionService...")
	sessions := s.activeSessions.GetPlaywrightActiveSessions()
	for conn, pair := range sessions {
		conn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseGoingAway, "Server shutting down"))
		conn.Close()
		if pair.ContainerInfo != nil {
			go s.dockerService.StopContainer(pair.ContainerInfo.ContainerID)
			s.sessionPublisher.CleanupSessionAndPublish(pair.SessionInfo)
			pair.Lock.Lock()
			pair.ContainerInfo = nil
			pair.Lock.Unlock()
		}
	}
	s.activeSessions.ClearPlaywrightWaitingQueue()
	s.dispatchStatusUpdate()
	log.Println("PlaywrightSessionService has been shut down.")
}

func (s *PlaywrightSessionService) dispatchStatusUpdate() {
	select {
	case s.statusChan <- struct{}{}:
	default:
	}
}