package services

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
	"github.com/nats-io/nats.go"
)

type SessionPublisher struct {
	nc *nats.Conn
}

func NewSessionPublisher(nc *nats.Conn) *SessionPublisher {
	return &SessionPublisher{nc: nc}
}

func (p *SessionPublisher) publish(info *dto.SessionInfo) {
	if p.nc == nil {
		return
	}
	data, err := json.Marshal(info)
	if err != nil {
		return
	}
	if err := p.nc.Publish("sessions.events", data); err != nil {
		fmt.Printf("Failed to publish session event: %v\n", err)
	}
}

func (p *SessionPublisher) CreateSessionAndPublish(platform, version string) *dto.SessionInfo {
	now := time.Now()
	info := &dto.SessionInfo{
		ID:        generateUUID(),
		StartTime: &now,
		Platform:  platform,
		Version:   version,
		Status:    "started",
	}
	p.publish(info)
	return info
}

func (p *SessionPublisher) EndSessionByRemoteAndPublish(info *dto.SessionInfo) *dto.SessionInfo {
	if info == nil {
		return nil
	}
	now := time.Now()
	info.EndTime = &now
	info.Status = "finished"
	info.EndedBy = "by remote"
	p.publish(info)
	return info
}

func (p *SessionPublisher) ErrorSessionAndPublish(info *dto.SessionInfo) *dto.SessionInfo {
	if info == nil {
		return nil
	}
	now := time.Now()
	info.EndTime = &now
	info.Status = "error"
	info.EndedBy = "error"
	p.publish(info)
	return info
}

func (p *SessionPublisher) EndInactiveSessionAndPublish(info *dto.SessionInfo) *dto.SessionInfo {
	if info == nil {
		return nil
	}
	now := time.Now()
	info.EndTime = &now
	info.Status = "finished"
	info.EndedBy = "by inactive"
	p.publish(info)
	return info
}

func (p *SessionPublisher) CleanupSessionAndPublish(info *dto.SessionInfo) *dto.SessionInfo {
	if info == nil {
		return nil
	}
	now := time.Now()
	info.EndTime = &now
	info.Status = "finished"
	info.EndedBy = "cleanup"
	p.publish(info)
	return info
}

func generateUUID() string {
	b := make([]byte, 16)
	for i := range b {
		b[i] = byte(i)
	}
	return fmt.Sprintf("%x", b)
}