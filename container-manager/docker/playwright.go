package docker

import (
	"context"
	"fmt"
	"github.com/balakshievas/Jelenoid/container-manager/models"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/google/uuid"
	"log"
)

func (m *Manager) StartPlaywright(ctx context.Context, image string, version string) (*models.ContainerInfo, error) {
	if !m.ImageExists(ctx, image) {
		return nil, fmt.Errorf("no playwright image with name %s", image)
	}

	containerName := "jelenoid-playwright-" + uuid.New().String()[:8]

	initPtr := true
	hostConfig := &container.HostConfig{
		Init:        &initPtr,
		IpcMode:     "host",
		CapAdd:      []string{"SYS_ADMIN"},
		NetworkMode: container.NetworkMode(m.Config.DockerNetwork),
		ShmSize:     m.Config.PlaywrightShmSize,
		Tmpfs: map[string]string{
			"/tmp": "rw,noexec,nosuid,size=" + m.Config.PlaywrightTmpfsSize,
		},
	}

	cmd := []string{"npx", "-y", "playwright@" + version, "run-server", "--port", m.Config.PlaywrightPort, "--host", "0.0.0.0"}

	resp, err := m.Cli.ContainerCreate(ctx, &container.Config{
		Image: image,
		Cmd:   cmd,
	}, hostConfig, &network.NetworkingConfig{}, nil, containerName)

	if err != nil {
		return nil, err
	}

	if err := m.Cli.ContainerStart(ctx, resp.ID, container.StartOptions{}); err != nil {
		m.StopContainer(context.Background(), resp.ID)
		return nil, err
	}

	log.Printf("Container %s started. Waiting for Playwright service...", resp.ID)

	if !m.WaitForPort(containerName, m.Config.PlaywrightPort) {
		m.StopContainer(context.Background(), resp.ID)
		return nil, fmt.Errorf("playwright service in container %s did not start", resp.ID)
	}

	return models.NewContainerInfo(resp.ID, containerName), nil
}
