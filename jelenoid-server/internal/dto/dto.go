package dto

import (
	"sync/atomic"
	"time"
)

type BrowserInfo struct {
	Name            string `json:"name"`
	Version         string `json:"version"`
	DockerImageName string `json:"dockerImageName"`
	IsDefault       bool   `json:"isDefault"`
}

type ContainerInfo struct {
	ContainerID   string    `json:"containerId"`
	ContainerName string    `json:"containerName"`
	LastActivity  int64     `json:"lastActivity"`
	StartTime     time.Time `json:"startTime"`
	BaseURL       string    `json:"-"`
}

func NewContainerInfo(containerID, containerName string) *ContainerInfo {
	ci := &ContainerInfo{
		ContainerID:   containerID,
		ContainerName: containerName,
		StartTime:     time.Now(),
		BaseURL:       "http://" + containerName + ":4444",
	}
	ci.UpdateActivity()
	return ci
}

func (c *ContainerInfo) UpdateActivity() {
	atomic.StoreInt64(&c.LastActivity, time.Now().UnixMilli())
}

func (c *ContainerInfo) GetLastActivity() int64 {
	return atomic.LoadInt64(&c.LastActivity)
}

type ContainerInfoRecord struct {
	ContainerID   string `json:"containerId"`
	ContainerName string `json:"containerName"`
}

type SeleniumSession struct {
	HubSessionID   string         `json:"hubSessionId"`
	RemoteSessionID string        `json:"remoteSessionId"`
	BrowserName    string         `json:"browserName"`
	Version        string         `json:"version"`
	VNCEnabled     bool           `json:"vncEnabled"`
	ContainerInfo  *ContainerInfo `json:"containerInfo"`
}

func (s *SeleniumSession) UpdateActivity() {
	if s.ContainerInfo != nil {
		s.ContainerInfo.UpdateActivity()
	}
}

func (s *SeleniumSession) GetLastActivity() int64 {
	if s.ContainerInfo != nil {
		return s.ContainerInfo.GetLastActivity()
	}
	return 0
}

func (s *SeleniumSession) GetStartTime() time.Time {
	if s.ContainerInfo != nil {
		return s.ContainerInfo.StartTime
	}
	return time.Time{}
}

type PendingRequest struct {
	RequestBody map[string]interface{}    `json:"requestBody"`
	Browser     string                    `json:"browser"`
	Version     string                    `json:"version"`
	Future      chan PendingRequestResult `json:"-"`
	QueuedTime  time.Time                 `json:"queuedTime"`
	StartTime   int64                     `json:"startTime"`
}

type PendingRequestResult struct {
	Response map[string]interface{}
	Err      error
}

type QueuedRequestInfo struct {
	Browser    string    `json:"browser"`
	Version    string    `json:"version"`
	QueuedTime time.Time `json:"queuedTime"`
}

type StatusResponse struct {
	Selenium   SeleniumStat   `json:"seleniumStat"`
	Playwright PlaywrightStat `json:"playwrightStat"`
}

type SeleniumStat struct {
	Total           int               `json:"total"`
	Used            int               `json:"used"`
	Queued          int               `json:"queued"`
	InProgress      int               `json:"inProgress"`
	ActiveSessions  []SeleniumSession `json:"activeSeleniumSessions"`
	QueuedSessions  []QueuedRequestInfo `json:"queuedSeleniumSession"`
}

type PlaywrightStat struct {
	MaxSessions         int               `json:"maxPlaywrightSessionsSize"`
	ActiveSessions      int               `json:"activePlaywrightSessionsSize"`
	QueuedSessions      int               `json:"queuedPlaywrightSessionsSize"`
	ActiveSessionPairs  []SessionPairInfo `json:"activePlaywrightSessions"`
	QueuedSessionPairs  []SessionPairInfo `json:"queuedPlaywrightSessions"`
}

type SessionPairInfo struct {
	ClientSessionID  string         `json:"clientSessionId"`
	ClientSessionURL  string         `json:"clientSessionUrl"`
	ContainerClientURL string        `json:"containerClientUrl"`
	PlaywrightVersion string         `json:"playwrightVersion"`
	ContainerInfo     *ContainerInfo `json:"containerInfo"`
}

type BrowsersConfig map[string]BrowserEntry

type BrowserEntry struct {
	Default  string                       `json:"default"`
	Versions map[string]BrowserVersionInfo `json:"versions"`
}

type BrowserVersionInfo struct {
	Image string `json:"image"`
}