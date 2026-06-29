package services

import (
	"net/http"
	"net/http/httputil"
	"sync"
	"sync/atomic"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/gorilla/websocket"
)

type sessionEntry struct {
	session *dto.SeleniumSession
	proxy   *httputil.ReverseProxy
}

type ActiveSessionsService struct {
	seleniumSessionLimit  int
	seleniumQueueLimit    int
	sessionTimeoutMs      int64
	queueTimeoutMs        int64
	playwrightMaxSessions int
	playwrightQueueLimit  int

	seleniumSessionsMu sync.RWMutex
	seleniumSessions   map[string]*sessionEntry
	seleniumInProgress atomic.Int32

	seleniumQueue   *queue[*dto.PendingRequest]
	seleniumQueueMu sync.Mutex

	playwrightSessionsMu sync.RWMutex
	playwrightSessions   map[*websocket.Conn]*PlaywrightSessionPair
	playwrightSemaphore  chan struct{}

	playwrightQueue   *queue[*PlaywrightQueuedSession]
	playwrightQueueMu sync.Mutex

	dockerService   *DockerExternalService
	seleniumService *SeleniumSessionService
	statusChan      chan struct{}
	enableQueue     bool
}

type PlaywrightSessionPair struct {
	ClientConn            *websocket.Conn
	ContainerConn         *websocket.Conn
	ContainerInfo         *dto.ContainerInfo
	PoolEntry             *poolEntry
	Version               string
	ConnectionEstablished atomic.Bool
	Lock                  sync.Mutex
	PendingMessages       [][]byte
	RequestHeaders        http.Header
}

type PlaywrightQueuedSession struct {
	Pair *PlaywrightSessionPair
}

func NewActiveSessionsService(
	seleniumSessionLimit, seleniumQueueLimit int,
	sessionTimeoutMs, queueTimeoutMs int64,
	playwrightMaxSessions, playwrightQueueLimit int,
	dockerService *DockerExternalService,
	statusChan chan struct{},
	enableQueue bool,
) *ActiveSessionsService {
	return &ActiveSessionsService{
		seleniumSessionLimit:  seleniumSessionLimit,
		seleniumQueueLimit:    seleniumQueueLimit,
		sessionTimeoutMs:      sessionTimeoutMs,
		queueTimeoutMs:        queueTimeoutMs,
		playwrightMaxSessions: playwrightMaxSessions,
		playwrightQueueLimit:  playwrightQueueLimit,
		seleniumSessions:      make(map[string]*sessionEntry),
		seleniumQueue:         newQueue[*dto.PendingRequest](seleniumQueueLimit),
		playwrightSessions:    make(map[*websocket.Conn]*PlaywrightSessionPair),
		playwrightSemaphore:   make(chan struct{}, playwrightMaxSessions),
		playwrightQueue:       newQueue[*PlaywrightQueuedSession](playwrightQueueLimit),
		dockerService:         dockerService,
		statusChan:            statusChan,
		enableQueue:           enableQueue,
	}
}

func (s *ActiveSessionsService) SetSeleniumService(svc *SeleniumSessionService) {
	s.seleniumService = svc
}

func (s *ActiveSessionsService) IsQueueEnabled() bool {
	return s.enableQueue
}

func (s *ActiveSessionsService) TryReserveSlot() bool {
	limit := int32(s.seleniumSessionLimit)
	for {
		old := s.seleniumInProgress.Load()
		if old >= limit {
			return false
		}
		if s.seleniumInProgress.CompareAndSwap(old, old+1) {
			return true
		}
	}
}

func (s *ActiveSessionsService) ReleaseSlot() {
	s.seleniumInProgress.Add(-1)
}

func (s *ActiveSessionsService) SessionSuccessfullyCreated(hubSessionID string, session *dto.SeleniumSession,
	proxy *httputil.ReverseProxy) {
	s.seleniumSessionsMu.Lock()
	s.seleniumSessions[hubSessionID] = &sessionEntry{session: session, proxy: proxy}
	s.seleniumSessionsMu.Unlock()
}

func (s *ActiveSessionsService) SessionDeleted(hubSessionID string) *dto.SeleniumSession {
	s.seleniumSessionsMu.Lock()
	entry, ok := s.seleniumSessions[hubSessionID]
	if !ok {
		s.seleniumSessionsMu.Unlock()
		return nil
	}
	delete(s.seleniumSessions, hubSessionID)
	s.seleniumSessionsMu.Unlock()
	s.ReleaseSlot()
	return entry.session
}

func (s *ActiveSessionsService) Get(sessionID string) *dto.SeleniumSession {
	s.seleniumSessionsMu.RLock()
	defer s.seleniumSessionsMu.RUnlock()
	entry, ok := s.seleniumSessions[sessionID]
	if !ok {
		return nil
	}
	return entry.session
}

func (s *ActiveSessionsService) GetProxy(sessionID string) *httputil.ReverseProxy {
	s.seleniumSessionsMu.RLock()
	defer s.seleniumSessionsMu.RUnlock()
	entry, ok := s.seleniumSessions[sessionID]
	if !ok {
		return nil
	}
	return entry.proxy
}

func (s *ActiveSessionsService) OfferToQueue(req *dto.PendingRequest) bool {
	s.seleniumQueueMu.Lock()
	defer s.seleniumQueueMu.Unlock()
	if s.seleniumQueue.len() >= s.seleniumQueueLimit {
		return false
	}
	s.seleniumQueue.push(req)
	return true
}

func (s *ActiveSessionsService) PollFromQueue() *dto.PendingRequest {
	s.seleniumQueueMu.Lock()
	defer s.seleniumQueueMu.Unlock()
	req, _ := s.seleniumQueue.pop()
	return req
}

func (s *ActiveSessionsService) GetQueueSize() int {
	s.seleniumQueueMu.Lock()
	defer s.seleniumQueueMu.Unlock()
	return s.seleniumQueue.len()
}

func (s *ActiveSessionsService) GetInProgressCount() int {
	return int(s.seleniumInProgress.Load())
}

func (s *ActiveSessionsService) GetSeleniumActiveSessions() map[string]*dto.SeleniumSession {
	s.seleniumSessionsMu.RLock()
	defer s.seleniumSessionsMu.RUnlock()
	result := make(map[string]*dto.SeleniumSession, len(s.seleniumSessions))
	for id, entry := range s.seleniumSessions {
		result[id] = entry.session
	}
	return result
}

func (s *ActiveSessionsService) GetSeleniumPendingRequests() []*dto.PendingRequest {
	s.seleniumQueueMu.Lock()
	defer s.seleniumQueueMu.Unlock()
	return s.seleniumQueue.snapshot()
}

func (s *ActiveSessionsService) GetSeleniumSessionLimit() int {
	return s.seleniumSessionLimit
}

func (s *ActiveSessionsService) GetPlaywrightMaxSessions() int {
	return s.playwrightMaxSessions
}

func (s *ActiveSessionsService) DispatchStatus() {
	select {
	case s.statusChan <- struct{}{}:
	default:
	}
}

func (s *ActiveSessionsService) CheckInactiveSessions() {
	now := time.Now().UnixMilli()

	type timedOutSession struct {
		containerID string
	}

	s.seleniumSessionsMu.Lock()
	var timedOut []timedOutSession
	for id, entry := range s.seleniumSessions {
		if now-entry.session.GetLastActivity() > s.sessionTimeoutMs {
			timedOut = append(timedOut, timedOutSession{
				containerID: entry.session.ContainerInfo.ContainerID,
			})
			delete(s.seleniumSessions, id)
		}
	}
	s.seleniumSessionsMu.Unlock()

	for range timedOut {
		s.ReleaseSlot()
	}
	for _, t := range timedOut {
		go s.dockerService.StopContainer(t.containerID)
	}
	if len(timedOut) > 0 && s.seleniumService != nil {
		go s.seleniumService.ProcessQueue()
	}

	s.seleniumQueueMu.Lock()
	s.seleniumQueue.retainIf(func(req *dto.PendingRequest) bool {
		if now-req.StartTime > s.queueTimeoutMs {
			req.Future <- dto.PendingRequestResult{
				Response: map[string]interface{}{
					"value": map[string]interface{}{
						"error":   "session not created",
						"message": "Queue timeout",
					},
				},
			}
			return false
		}
		return true
	})
	s.seleniumQueueMu.Unlock()

	s.DispatchStatus()
}

func (s *ActiveSessionsService) Cleanup() {
	s.seleniumSessionsMu.Lock()
	for id, entry := range s.seleniumSessions {
		go s.dockerService.StopContainer(entry.session.ContainerInfo.ContainerID)
		delete(s.seleniumSessions, id)
	}
	s.seleniumSessionsMu.Unlock()

	s.seleniumQueueMu.Lock()
	s.seleniumQueue.clear()
	s.seleniumQueueMu.Unlock()

	s.seleniumInProgress.Store(0)
}

func (s *ActiveSessionsService) OfferPlaywrightQueue(pair *PlaywrightSessionPair) bool {
	s.playwrightQueueMu.Lock()
	defer s.playwrightQueueMu.Unlock()
	if s.playwrightQueue.len() >= s.playwrightQueueLimit {
		return false
	}
	s.playwrightQueue.push(&PlaywrightQueuedSession{Pair: pair})
	return true
}

func (s *ActiveSessionsService) PollFromPlaywrightQueue() *PlaywrightSessionPair {
	s.playwrightQueueMu.Lock()
	defer s.playwrightQueueMu.Unlock()
	q, ok := s.playwrightQueue.pop()
	if !ok {
		return nil
	}
	return q.Pair
}

func (s *ActiveSessionsService) RemoveFromPlaywrightQueue(conn *websocket.Conn) bool {
	s.playwrightQueueMu.Lock()
	defer s.playwrightQueueMu.Unlock()
	return s.playwrightQueue.removeFirst(func(q *PlaywrightQueuedSession) bool {
		return q.Pair.ClientConn == conn
	})
}

func (s *ActiveSessionsService) PutPlaywrightActiveSession(conn *websocket.Conn, pair *PlaywrightSessionPair) {
	s.playwrightSessionsMu.Lock()
	s.playwrightSessions[conn] = pair
	s.playwrightSessionsMu.Unlock()
}

func (s *ActiveSessionsService) RemovePlaywrightActiveSession(conn *websocket.Conn) *PlaywrightSessionPair {
	s.playwrightSessionsMu.Lock()
	pair, ok := s.playwrightSessions[conn]
	if !ok {
		s.playwrightSessionsMu.Unlock()
		return nil
	}
	delete(s.playwrightSessions, conn)
	s.playwrightSessionsMu.Unlock()
	return pair
}

func (s *ActiveSessionsService) GetPlaywrightActiveSession(conn *websocket.Conn) *PlaywrightSessionPair {
	s.playwrightSessionsMu.RLock()
	defer s.playwrightSessionsMu.RUnlock()
	return s.playwrightSessions[conn]
}

func (s *ActiveSessionsService) TryAcquirePlaywrightSlot() bool {
	select {
	case s.playwrightSemaphore <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s *ActiveSessionsService) ReleasePlaywrightSlot() {
	<-s.playwrightSemaphore
}

func (s *ActiveSessionsService) AvailablePlaywrightSlots() int {
	return len(s.playwrightSemaphore)
}

func (s *ActiveSessionsService) UsedPlaywrightSlots() int {
	return s.playwrightMaxSessions - s.AvailablePlaywrightSlots()
}

func (s *ActiveSessionsService) GetPlaywrightActiveSessions() map[*websocket.Conn]*PlaywrightSessionPair {
	s.playwrightSessionsMu.RLock()
	defer s.playwrightSessionsMu.RUnlock()
	result := make(map[*websocket.Conn]*PlaywrightSessionPair, len(s.playwrightSessions))
	for conn, pair := range s.playwrightSessions {
		result[conn] = pair
	}
	return result
}

func (s *ActiveSessionsService) GetPlaywrightWaitingQueue() []*PlaywrightSessionPair {
	s.playwrightQueueMu.Lock()
	defer s.playwrightQueueMu.Unlock()
	items := s.playwrightQueue.snapshot()
	result := make([]*PlaywrightSessionPair, len(items))
	for i, q := range items {
		result[i] = q.Pair
	}
	return result
}

func (s *ActiveSessionsService) ClearPlaywrightWaitingQueue() {
	s.playwrightQueueMu.Lock()
	s.playwrightQueue.clear()
	s.playwrightQueueMu.Unlock()
}

func (s *ActiveSessionsService) GetPlaywrightQueueLimit() int {
	return s.playwrightQueueLimit
}
