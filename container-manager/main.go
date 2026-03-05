package main

import (
	"log"
	"net/http"

	"github.com/balakshievas/Jelenoid/container-manager/api"
	"github.com/balakshievas/Jelenoid/container-manager/config"
	"github.com/balakshievas/Jelenoid/container-manager/docker"
)

func main() {
	cfg := config.LoadConfig()

	dockerManager, err := docker.NewManager(cfg)
	if err != nil {
		log.Fatalf("Failed to initialize Docker client: %v", err)
	}

	handler := &api.Handler{Manager: dockerManager}

	// Маршрутизатор (использует новые возможности роутинга Go 1.22)
	mux := http.NewServeMux()

	// General endpoints
	mux.HandleFunc("POST /api/containers/{containerId}/file", handler.UploadFile)
	mux.HandleFunc("GET /api/containers/{containerId}/logs", handler.StreamLogs)
	mux.HandleFunc("DELETE /api/containers", handler.KillContainer)

	// Playwright & Selenium
	mux.HandleFunc("POST /api/containers/playwright", handler.StartPlaywright)
	mux.HandleFunc("POST /api/containers/selenium", handler.StartSelenium)

	// Healthcheck
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"UP"}`))
	})

	log.Printf("Starting Container Manager on port %s...", cfg.Port)
	if err := http.ListenAndServe(":"+cfg.Port, mux); err != nil {
		log.Fatalf("Server stopped: %v", err)
	}
}
