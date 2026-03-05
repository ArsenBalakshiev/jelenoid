package models

import "time"

type ContainerInfo struct {
	ContainerID   string    `json:"containerId"`
	ContainerName string    `json:"containerName"`
	LastActivity  int64     `json:"lastActivity"`
	StartTime     time.Time `json:"startTime"`
}

func NewContainerInfo(id, name string) *ContainerInfo {
	return &ContainerInfo{
		ContainerID:   id,
		ContainerName: name,
		StartTime:     time.Now(),
		LastActivity:  time.Now().UnixMilli(),
	}
}

func (c *ContainerInfo) UpdateActivity() {
	c.LastActivity = time.Now().UnixMilli()
}
