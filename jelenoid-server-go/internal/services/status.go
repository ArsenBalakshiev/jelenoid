package services

import (
	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

func NewStatusService(activeSessions *ActiveSessionsService) *StatusService {
	return &StatusService{activeSessions: activeSessions}
}

func (s *StatusService) BuildStatus() *dto.StatusResponse {
	sessions := s.activeSessions.GetSeleniumActiveSessions()
	var seleniumSessions []dto.SeleniumSession
	for _, ss := range sessions {
		seleniumSessions = append(seleniumSessions, *ss)
	}

	pendingReqs := s.activeSessions.GetSeleniumPendingRequests()
	var queuedInfos []dto.QueuedRequestInfo
	for _, req := range pendingReqs {
		browserName := "unknown"
		browserVersion := "unknown"
		if caps, ok := req.RequestBody["capabilities"].(map[string]interface{}); ok {
			alwaysMatch, _ := caps["alwaysMatch"].(map[string]interface{})
			firstMatch, _ := caps["firstMatch"].([]interface{})
			if alwaysMatch == nil {
				alwaysMatch = make(map[string]interface{})
			}
			for _, fm := range firstMatch {
				fmMap, ok := fm.(map[string]interface{})
				if !ok {
					continue
				}
				merged := make(map[string]interface{})
				for k, v := range alwaysMatch {
					merged[k] = v
				}
				for k, v := range fmMap {
					merged[k] = v
				}
				if name, ok := merged["browserName"].(string); ok {
					browserName = name
				}
				if ver, ok := merged["browserVersion"].(string); ok {
					browserVersion = ver
				}
			}
		}
		queuedInfos = append(queuedInfos, dto.QueuedRequestInfo{
			Browser:    browserName,
			Version:    browserVersion,
			QueuedTime: req.QueuedTime,
		})
	}

	seleniumStat := dto.SeleniumStat{
		Total:          s.activeSessions.GetSeleniumSessionLimit(),
		Used:           len(seleniumSessions),
		Queued:         s.activeSessions.GetQueueSize(),
		InProgress:     s.activeSessions.GetInProgressCount(),
		ActiveSessions:  seleniumSessions,
		QueuedSessions:  queuedInfos,
	}

	activePW := s.activeSessions.GetPlaywrightActiveSessions()
	queuedPW := s.activeSessions.GetPlaywrightWaitingQueue()

	var activePairInfos []dto.SessionPairInfo
	for _, pair := range activePW {
		info := dto.SessionPairInfo{
			PlaywrightVersion: pair.Version,
		}
		if pair.ClientConn != nil {
			info.ClientSessionID = pair.ClientConn.RemoteAddr().String()
			info.ClientSessionURL = pair.ClientConn.LocalAddr().String()
		}
		if pair.ContainerConn != nil {
			info.ContainerClientURL = pair.ContainerConn.LocalAddr().String()
		}
		if pair.ContainerInfo != nil {
			info.ContainerInfo = pair.ContainerInfo
		}
		activePairInfos = append(activePairInfos, info)
	}

	var queuedPairInfos []dto.SessionPairInfo
	for _, pair := range queuedPW {
		info := dto.SessionPairInfo{
			PlaywrightVersion: pair.Version,
		}
		if pair.ClientConn != nil {
			info.ClientSessionID = pair.ClientConn.RemoteAddr().String()
			info.ClientSessionURL = pair.ClientConn.LocalAddr().String()
		}
		if pair.ContainerInfo != nil {
			info.ContainerInfo = pair.ContainerInfo
		}
		queuedPairInfos = append(queuedPairInfos, info)
	}

	playwrightStat := dto.PlaywrightStat{
		MaxSessions:        s.activeSessions.GetPlaywrightMaxSessions(),
		ActiveSessions:     s.activeSessions.UsedPlaywrightSlots(),
		QueuedSessions:     len(queuedPW),
		ActiveSessionPairs: activePairInfos,
		QueuedSessionPairs: queuedPairInfos,
	}

	return &dto.StatusResponse{
		Selenium:   seleniumStat,
		Playwright: playwrightStat,
	}
}