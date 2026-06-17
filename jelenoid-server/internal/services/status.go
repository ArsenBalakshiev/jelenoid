package services

import (
	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

func (s *StatusService) BuildStatus() *dto.StatusResponse {
	sessions := s.activeSessions.GetSeleniumActiveSessions()
	var seleniumSessions []dto.SeleniumSession
	for _, ss := range sessions {
		seleniumSessions = append(seleniumSessions, *ss)
	}

	pendingReqs := s.activeSessions.GetSeleniumPendingRequests()
	queuedInfos := make([]dto.QueuedRequestInfo, 0, len(pendingReqs))
	for _, req := range pendingReqs {
		queuedInfos = append(queuedInfos, dto.QueuedRequestInfo{
			Browser:    nonemptyString(req.Browser, "unknown"),
			Version:    req.Version,
			QueuedTime: req.QueuedTime,
		})
	}

	seleniumStat := dto.SeleniumStat{
		Total:          s.activeSessions.GetSeleniumSessionLimit(),
		Used:           len(seleniumSessions),
		Queued:         s.activeSessions.GetQueueSize(),
		InProgress:     s.activeSessions.GetInProgressCount(),
		ActiveSessions: seleniumSessions,
		QueuedSessions: queuedInfos,
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

	if s.pool != nil {
		ps := s.pool.Stats()
		playwrightStat.Pool = &dto.PlaywrightPoolStatsDTO{
			Enabled: ps.Enabled,
			MaxSize: ps.MaxSize,
			Total:   ps.Total,
			ByKey:   make(map[string]dto.PlaywrightPoolKeyStatsDTO, len(ps.ByKey)),
		}
		for k, v := range ps.ByKey {
			playwrightStat.Pool.ByKey[k] = dto.PlaywrightPoolKeyStatsDTO{
				Starting: v.Starting,
				Ready:    v.Ready,
				InUse:    v.InUse,
				Draining: v.Draining,
			}
		}
	}

	return &dto.StatusResponse{
		Selenium:   seleniumStat,
		Playwright: playwrightStat,
	}
}

func nonemptyString(s, fallback string) string {
	if s == "" {
		return fallback
	}
	return s
}
