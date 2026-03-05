package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/balakshievas/Jelenoid/container-manager/docker"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/pkg/stdcopy"
)

type Handler struct {
	Manager *docker.Manager
}

// Заменяет GlobalExceptionHandler
func respondWithError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)

	var errType string
	switch status {
	case 401, 403:
		errType = "session not created"
	case 404:
		errType = "invalid session id"
	default:
		errType = "unknown error"
	}

	json.NewEncoder(w).Encode(map[string]any{
		"value": map[string]string{
			"error":      errType,
			"message":    message,
			"stacktrace": "",
		},
	})
}

func (h *Handler) UploadFile(w http.ResponseWriter, r *http.Request) {
	containerID := r.PathValue("containerId")
	file, _, err := r.FormFile("file")
	if err != nil {
		respondWithError(w, http.StatusBadRequest, "Missing file")
		return
	}
	defer file.Close()

	fileBytes, _ := io.ReadAll(file)
	path, err := h.Manager.CopyFileToContainer(r.Context(), containerID, fileBytes)
	if err != nil {
		respondWithError(w, http.StatusInternalServerError, err.Error())
		return
	}

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Write([]byte(path))
}

func (h *Handler) KillContainer(w http.ResponseWriter, r *http.Request) {
	containerID := r.URL.Query().Get("containerId")
	if containerID == "" {
		respondWithError(w, http.StatusBadRequest, "containerId is required")
		return
	}
	success := h.Manager.StopContainer(r.Context(), containerID)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(success)
}

func (h *Handler) StartPlaywright(w http.ResponseWriter, r *http.Request) {
	image := r.URL.Query().Get("image")
	version := r.URL.Query().Get("playwrightVersion")

	info, err := h.Manager.StartPlaywright(r.Context(), image, version)
	if err != nil {
		respondWithError(w, http.StatusNotFound, err.Error()) // Имитация NoImageException
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(info)
}

func (h *Handler) StartSelenium(w http.ResponseWriter, r *http.Request) {
	image := r.URL.Query().Get("image")
	isVncEnabled, _ := strconv.ParseBool(r.URL.Query().Get("isVncEnabled"))

	info, err := h.Manager.StartSelenium(r.Context(), image, isVncEnabled)
	if err != nil {
		respondWithError(w, http.StatusNotFound, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(info)
}

func (h *Handler) StreamLogs(w http.ResponseWriter, r *http.Request) {
	containerID := r.PathValue("containerId")

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	logsReader, err := h.Manager.Cli.ContainerLogs(r.Context(), containerID, container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Follow:     true,
		Tail:       "all",
	})
	if err != nil {
		return
	}
	defer logsReader.Close()

	// Используем кастомный Writer для упаковки логов Docker (мультиплексированных StdCopy) в SSE
	sseWriter := &SseWriter{w: w, flusher: flusher}
	_, _ = stdcopy.StdCopy(sseWriter, sseWriter, logsReader)
}

// SseWriter оборачивает потоки Stdout/Stderr в формат SSE (Server-Sent Events)
type SseWriter struct {
	w       io.Writer
	flusher http.Flusher
}

func (s *SseWriter) Write(p []byte) (n int, err error) {
	if len(p) == 0 {
		return 0, nil
	}

	// 1. Убираем перенос строки на конце, если он есть, чтобы не создать пустую data:
	str := strings.TrimSuffix(string(p), "\n")

	// 2. Заменяем внутренние переносы строк на перенос + префикс
	str = strings.ReplaceAll(str, "\n", "\ndata: ")

	// 3. Отправляем в поток, закрывая событие двумя переносами \n\n
	fmt.Fprintf(s.w, "data: %s\n\n", str)
	s.flusher.Flush()

	return len(p), nil
}
