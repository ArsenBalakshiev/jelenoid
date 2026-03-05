package docker

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"github.com/docker/docker/api/types/image"
	"io"
	"log"
	"net"
	"time"

	"github.com/balakshievas/Jelenoid/container-manager/config"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
)

type Manager struct {
	Cli    *client.Client
	Config *config.Config
}

func NewManager(cfg *config.Config) (*Manager, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, err
	}
	return &Manager{Cli: cli, Config: cfg}, nil
}

func (m *Manager) StopContainer(ctx context.Context, containerID string) bool {
	log.Printf("Stopping and removing container %s", containerID)

	stopTimeout := int(m.Config.CleanupTimeout.Seconds())
	err := m.Cli.ContainerStop(ctx, containerID, container.StopOptions{Timeout: &stopTimeout})
	if err != nil {
		log.Printf("Container %s might be already stopped: %v", containerID, err)
	}

	err = m.Cli.ContainerRemove(ctx, containerID, container.RemoveOptions{Force: true})
	if err != nil {
		log.Printf("Failed to remove container %s: %v", containerID, err)
		return false
	}
	return true
}

func (m *Manager) ImageExists(ctx context.Context, imageName string) bool {
	images, err := m.Cli.ImageList(ctx, image.ListOptions{})
	if err != nil {
		return false
	}
	for _, img := range images {
		for _, tag := range img.RepoTags {
			if tag == imageName || tag == imageName+":latest" {
				return true
			}
		}
	}
	return false
}

func (m *Manager) WaitForPort(host string, port string) bool {
	deadline := time.Now().Add(m.Config.StartingTimeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, port), 500*time.Millisecond)
		if err == nil {
			conn.Close()
			return true
		}
		time.Sleep(1 * time.Second)
	}
	return false
}

// Конвертирует загруженный ZIP архив в TAR и отправляет в контейнер
func (m *Manager) CopyFileToContainer(ctx context.Context, containerID string, rawZipBytes []byte) (string, error) {
	zipReader, err := zip.NewReader(bytes.NewReader(rawZipBytes), int64(len(rawZipBytes)))
	if err != nil {
		return "", err
	}

	var fileName string
	var fileContent []byte
	maxFileSize := int64(50 * 1024 * 1024)

	for _, file := range zipReader.File {
		if file.FileInfo().IsDir() {
			continue
		}
		fileName = file.FileInfo().Name()
		rc, err := file.Open()
		if err != nil {
			return "", err
		}

		var buf bytes.Buffer
		n, err := io.CopyN(&buf, rc, maxFileSize+1)
		rc.Close()
		if n > maxFileSize {
			return "", errors.New("file exceeds maximum allowed size (Zip Bomb protection)")
		}
		if err != nil && err != io.EOF {
			return "", err
		}
		fileContent = buf.Bytes()
		break
	}

	if fileName == "" {
		return "", errors.New("ZIP archive is empty or contains only directories")
	}

	var tarBuffer bytes.Buffer
	tarWriter := tar.NewWriter(&tarBuffer)
	hdr := &tar.Header{
		Name: fileName,
		Mode: 0644,
		Size: int64(len(fileContent)),
	}
	if err := tarWriter.WriteHeader(hdr); err != nil {
		return "", err
	}
	if _, err := tarWriter.Write(fileContent); err != nil {
		return "", err
	}
	if err := tarWriter.Close(); err != nil {
		return "", err
	}

	err = m.Cli.CopyToContainer(ctx, containerID, "/", bytes.NewReader(tarBuffer.Bytes()), container.CopyToContainerOptions{})
	if err != nil {
		return "", fmt.Errorf("failed to copy file to container: %w", err)
	}

	return "/" + fileName, nil
}
