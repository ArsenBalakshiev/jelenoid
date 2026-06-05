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
	seleniumSessionLimit   int
	seleniumQueueLimit     int
	sessionTimeoutMs       int64
	queueTimeoutMs         int64
	playwrightMaxSessions  int
	playwrightQueueLimit   int

	seleniumSessions     sync.Map
	seleniumInProgress   atomic.Int32

	seleniumQueue     *queue[*dto.PendingRequest]
	seleniumQueueMu   sync.Mutex

	playwrightSessions   sync.Map
	playwrightSemaphore  chan struct{}

	playwrightQueue     *queue[*PlaywrightQueuedSession]
	playwrightQueueMu   sync.Mutex

	dockerService      *DockerExternalService
	seleniumService     *SeleniumSessionService
	statusChan          chan struct{}
	enableQueue         bool
}

type PlaywrightSessionPair struct {
	ClientConn                 *websocket.Conn
	ContainerConn              *websocket.Conn
	ContainerInfo              *dto.ContainerInfo
	Version                    string
	ConnectionEstablished      atomic.Bool
	Lock                       sync.Mutex
	PendingMessages            [][]byte
	RequestHeaders             http.Header
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
		seleniumQueueLimit:     seleniumQueueLimit,
		sessionTimeoutMs:       sessionTimeoutMs,
		queueTimeoutMs:         queueTimeoutMs,
		playwrightMaxSessions:  playwrightMaxSessions,
		playwrightQueueLimit:   playwrightQueueLimit,
		seleniumQueue:          newQueue[*dto.PendingRequest](seleniumQueueLimit),
		playwrightSemaphore:    make(chan struct{}, playwrightMaxSessions),
		playwrightQueue:        newQueue[*PlaywrightQueuedSession](playwrightQueueLimit),
		dockerService:          dockerService,
		statusChan:             statusChan,
		enableQueue:            enableQueue,
	}
}

func (s *ActiveSessionsService) SetSeleniumService(svc *SeleniumSessionService) {
	s.seleniumService = svc
}

func (s *ActiveSessionsService) IsQueueEnabled() bool {
	return s.enableQueue
}

func (s *ActiveSessionsService) TryReserveSlot() bool {
	current := s.seleniumInProgress.Add(1)
	if int(current) > s.seleniumSessionLimit {
		s.seleniumInProgress.Add(-1)
		return false
	}
	return true
}

func (s *ActiveSessionsService) ReleaseSlot() {
	s.seleniumInProgress.Add(-1)
}

func (s *ActiveSessionsService) SessionSuccessfullyCreated(hubSessionID string, session *dto.SeleniumSession, proxy *httputil.ReverseProxy) {
	s.seleniumSessions.Store(hubSessionID, &sessionEntry{session: session, proxy: proxy})
}

func (s *ActiveSessionsService) SessionDeleted(hubSessionID string) *dto.SeleniumSession {
	val, ok := s.seleniumSessions.LoadAndDelete(hubSessionID)
	if !ok {
		return nil
	}
	entry := val.(*sessionEntry)
	s.ReleaseSlot()
	return entry.session
}

func (s *ActiveSessionsService) Get(sessionID string) *dto.SeleniumSession {
	val, ok := s.seleniumSessions.Load(sessionID)
	if !ok {
		return nil
	}
	return val.(*sessionEntry).session
}

func (s *ActiveSessionsService) GetProxy(sessionID string) *httputil.ReverseProxy {
	val, ok := s.seleniumSessions.Load(sessionID)
	if !ok {
		return nil
	}
	return val.(*sessionEntry).proxy
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
	result := make(map[string]*dto.SeleniumSession)
	s.seleniumSessions.Range(func(key, value any) bool {
		result[key.(string)] = value.(*sessionEntry).session
		return true
	})
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

	var timedOut []timedOutSession
	s.seleniumSessions.Range(func(key, value any) bool {
		entry := value.(*sessionEntry)
		session := entry.session
		if now-session.GetLastActivity() > s.sessionTimeoutMs {
			s.seleniumSessions.Delete(key)
			timedOut = append(timedOut, timedOutSession{
				containerID: session.ContainerInfo.ContainerID,
			})
		}
		return true
	})

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
	s.seleniumSessions.Range(func(key, value any) bool {
		entry := value.(*sessionEntry)
		go s.dockerService.StopContainer(entry.session.ContainerInfo.ContainerID)
		s.seleniumSessions.Delete(key)
		return true
	})

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
	s.playwrightSessions.Store(conn, pair)
}

func (s *ActiveSessionsService) RemovePlaywrightActiveSession(conn *websocket.Conn) *PlaywrightSessionPair {
	val, ok := s.playwrightSessions.LoadAndDelete(conn)
	if !ok {
		return nil
	}
	return val.(*PlaywrightSessionPair)
}

func (s *ActiveSessionsService) GetPlaywrightActiveSession(conn *websocket.Conn) *PlaywrightSessionPair {
	val, ok := s.playwrightSessions.Load(conn)
	if !ok {
		return nil
	}
	return val.(*PlaywrightSessionPair)
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
	result := make(map[*websocket.Conn]*PlaywrightSessionPair)
	s.playwrightSessions.Range(func(key, value any) bool {
		result[key.(*websocket.Conn)] = value.(*PlaywrightSessionPair)
		return true
	})
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