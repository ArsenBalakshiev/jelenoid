package docker

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/balakshievas/Jelenoid/container-manager/models"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/google/uuid"
)

func (m *Manager) StartSelenium(ctx context.Context, image string, isVncEnabled bool) (*models.ContainerInfo, error) {
	if !m.ImageExists(ctx, image) {
		return nil, fmt.Errorf("no selenium image with name %s", image)
	}

	containerName := "jelenoid-session-" + uuid.New().String()

	hostConfig := &container.HostConfig{
		NetworkMode: container.NetworkMode(m.Config.DockerNetwork),
		ShmSize:     m.Config.SeleniumShmSize,
		Tmpfs: map[string]string{
			"/tmp": "rw,noexec,nosuid,size=" + m.Config.SeleniumTmpfsSize,
		},
	}

	var envVars []string
	if isVncEnabled {
		envVars = append(envVars, "ENABLE_VNC=true")
	}

	resp, err := m.Cli.ContainerCreate(ctx, &container.Config{
		Image: image,
		Env:   envVars,
	}, hostConfig, &network.NetworkingConfig{}, nil, containerName)

	if err != nil {
		return nil, err
	}

	if err := m.Cli.ContainerStart(ctx, resp.ID, container.StartOptions{}); err != nil {
		m.StopContainer(context.Background(), resp.ID)
		return nil, err
	}

	log.Printf("Started container %s with name %s", resp.ID, containerName)

	if err := m.waitForSeleniumReady(containerName); err != nil {
		log.Println("Container failed to become ready, stopping it...")
		m.StopContainer(context.Background(), resp.ID)
		return nil, err
	}

	return models.NewContainerInfo(resp.ID, containerName), nil
}

func (m *Manager) waitForSeleniumReady(containerIpAddress string) error {
	statusUrl := fmt.Sprintf("http://%s:4444/status", containerIpAddress)
	deadline := time.Now().Add(m.Config.StartingTimeout)

	client := &http.Client{Timeout: 2 * time.Second}

	for time.Now().Before(deadline) {
		resp, err := client.Get(statusUrl)
		if err == nil {
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 && strings.Contains(string(body), `"ready":true`) {
				time.Sleep(500 * time.Millisecond) // Tactical delay
				return nil
			}
		}
		time.Sleep(1 * time.Second)
	}
	return fmt.Errorf("container %s did not become ready in time", containerIpAddress)
}
