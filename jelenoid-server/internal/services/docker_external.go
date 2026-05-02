package services

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

type DockerExternalService struct {
	containerManagerAddr string
	httpClient           *http.Client
}

func NewDockerExternalService(containerManagerAddr string) *DockerExternalService {
	return &DockerExternalService{
		containerManagerAddr: containerManagerAddr,
		httpClient:           &http.Client{},
	}
}

func (s *DockerExternalService) StartSeleniumContainer(image string, isVncEnabled bool) (*dto.ContainerInfo, error) {
	url := fmt.Sprintf("%s/api/containers/selenium?image=%s&isVncEnabled=%v", s.containerManagerAddr, image, isVncEnabled)
	resp, err := s.httpClient.Post(url, "application/json", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to start selenium container: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("failed to start selenium container, status: %d, body: %s", resp.StatusCode, string(body))
	}

	var record dto.ContainerInfoRecord
	if err := json.NewDecoder(resp.Body).Decode(&record); err != nil {
		return nil, fmt.Errorf("failed to decode container info: %w", err)
	}

	return dto.NewContainerInfo(record.ContainerID, record.ContainerName), nil
}

func (s *DockerExternalService) StartPlaywrightContainer(image string, playwrightVersion string) (*dto.ContainerInfo, error) {
	url := fmt.Sprintf("%s/api/containers/playwright?image=%s&playwrightVersion=%s", s.containerManagerAddr, image, playwrightVersion)
	resp, err := s.httpClient.Post(url, "application/json", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to start playwright container: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("failed to start playwright container, status: %d, body: %s", resp.StatusCode, string(body))
	}

	var record dto.ContainerInfoRecord
	if err := json.NewDecoder(resp.Body).Decode(&record); err != nil {
		return nil, fmt.Errorf("failed to decode container info: %w", err)
	}

	return dto.NewContainerInfo(record.ContainerID, record.ContainerName), nil
}

func (s *DockerExternalService) StopContainer(containerID string) (bool, error) {
	url := fmt.Sprintf("%s/api/containers?containerId=%s", s.containerManagerAddr, containerID)
	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		return false, err
	}
	resp, err := s.httpClient.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	var result bool
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return false, err
	}
	return result, nil
}

func (s *DockerExternalService) CopyFileToContainer(containerID string, fileBytes []byte) (string, error) {
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile("file", "upload.zip")
	if err != nil {
		return "", err
	}
	if _, err := part.Write(fileBytes); err != nil {
		return "", err
	}
	writer.Close()

	url := fmt.Sprintf("%s/api/containers/%s/file", s.containerManagerAddr, containerID)
	req, err := http.NewRequest(http.MethodPost, url, &buf)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(body)), nil
}

func (s *DockerExternalService) StreamContainerLogs(containerID string) (<-chan []byte, error) {
	url := fmt.Sprintf("%s/api/containers/%s/logs", s.containerManagerAddr, containerID)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "text/event-stream")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("upstream error: %d", resp.StatusCode)
	}

	ch := make(chan []byte, 256)
	go func() {
		defer close(ch)
		defer resp.Body.Close()
		buf := make([]byte, 1024)
		for {
			n, err := resp.Body.Read(buf)
			if n > 0 {
				data := make([]byte, n)
				copy(data, buf[:n])
				ch <- data
			}
			if err != nil {
				return
			}
		}
	}()

	return ch, nil
}