package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/config"
	"github.com/balakshievas/jelenoid-server-go/internal/handlers"
	"github.com/balakshievas/jelenoid-server-go/internal/services"
)

func main() {
	cfg := config.Load()

	statusChan := make(chan struct{}, 256)
	sseHub := services.NewSSEHub()

	dockerService := services.NewDockerExternalService(cfg.ContainerManagerAddr)
	browserManager := services.NewBrowserManagerService(cfg.BrowsersConfigDir)
	activeSessions := services.NewActiveSessionsService(
		cfg.SessionLimit,
		cfg.QueueLimit,
		cfg.SessionTimeoutMs,
		cfg.QueueTimeoutMs,
		cfg.PlaywrightMaxSessions,
		cfg.PlaywrightQueueLimit,
		dockerService,
		statusChan,
		cfg.EnableQueue,
	)
	seleniumService := services.NewSeleniumSessionService(
		activeSessions,
		browserManager,
		dockerService,
		statusChan,
		cfg.PublicHost,
		cfg.ServerPort,
		cfg.PublicHost,
	)
	activeSessions.SetSeleniumService(seleniumService)

	statusService := services.NewStatusService(activeSessions)
	statusNotifier := services.NewStatusNotifier(sseHub, statusService)

	sseHub.SetInitialDataBuilder(func() services.SSEEvent {
		return services.SSEEvent{
			Event: "state-update",
			Data:  statusNotifier.GetStatus(),
		}
	})

	playwrightService := services.NewPlaywrightSessionService(
		activeSessions,
		dockerService,
		browserManager,
		statusChan,
		cfg.SessionTimeoutMs,
	)

	wdHubHandler := handlers.NewWdHubHandler(seleniumService, activeSessions)
	activeSessionsHandler := handlers.NewActiveSessionsHandler(activeSessions)
	browserManagerHandler := handlers.NewBrowserManagerHandler(browserManager)
	eventsHandler := handlers.NewEventsHandler(sseHub)
	logsHandler := handlers.NewLogsHandler(seleniumService)
	devToolsHandler := handlers.NewDevToolsProxyHandler(activeSessions)
	vncHandler := handlers.NewVncProxyHandler(activeSessions)

	mux := http.NewServeMux()

	mux.HandleFunc("POST /wd/hub/session", wdHubHandler.CreateSession)
	mux.HandleFunc("DELETE /wd/hub/session", wdHubHandler.DeleteSession)
	mux.HandleFunc("DELETE /wd/hub/session/{id}", wdHubHandler.DeleteSession)
	mux.HandleFunc("POST /wd/hub/session/{id}/se/file", wdHubHandler.UploadFile)
	mux.HandleFunc("POST /wd/hub/session/{id}/file", wdHubHandler.UploadFile)
	mux.HandleFunc("/wd/hub/session/{id}/", wdHubHandler.ProxyRequest)

	mux.HandleFunc("/api/limit/sessions", activeSessionsHandler.GetAllSessions)
	mux.HandleFunc("/api/limit/request", activeSessionsHandler.GetAllPendingRequests)
	mux.HandleFunc("/api/limit/sessions/size", activeSessionsHandler.GetAllSessionsSize)
	mux.HandleFunc("/api/limit/request/size", activeSessionsHandler.GetAllPendingRequestsSize)

	mux.HandleFunc("/api/browsers", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			browserManagerHandler.GetBrowsers(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/api/browsers/add", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPut {
			browserManagerHandler.AddBrowser(w, r)
		} else {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/api/browsers/delete", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			browserManagerHandler.DeleteBrowser(w, r)
		} else {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})

	mux.HandleFunc("/events", eventsHandler.Subscribe)
	mux.HandleFunc("/logs/", logsHandler.ServeHTTP)
	mux.HandleFunc("/vnc/", vncHandler.ServeHTTP)

	mux.HandleFunc("/session/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/se/cdp") {
			devToolsHandler.ServeHTTP(w, r)
			return
		}
		http.NotFound(w, r)
	})

	mux.HandleFunc("/playwright", playwrightService.ServeHTTP)
	mux.HandleFunc("/playwright/", playwrightService.ServeHTTP)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/playwright-") {
			playwrightService.ServeHTTP(w, r)
			return
		}
		http.NotFound(w, r)
	})

	handler := handlers.CORSMiddleware(cfg.UIHosts, mux)

	go func() {
		var timerCh <-chan time.Time
		for {
			select {
			case <-statusChan:
				if timerCh == nil {
					timerCh = time.After(100 * time.Millisecond)
				}
			case <-timerCh:
				statusNotifier.OnStatusChanged()
				timerCh = nil
			}
		}
	}()

	go func() {
		ticker := time.NewTicker(time.Duration(cfg.StartupTimeoutMs) * time.Millisecond)
		defer ticker.Stop()
		for range ticker.C {
			activeSessions.CheckInactiveSessions()
			playwrightService.CheckSessionTimeouts()
		}
	}()

	addr := fmt.Sprintf(":%d", cfg.ServerPort)
	log.Printf("Starting jelenoid server on %s", addr)

	server := &http.Server{
		Addr:    addr,
		Handler: handler,
	}

	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server failed: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down server...")
	activeSessions.Cleanup()
	playwrightService.Shutdown()
	browserManager.Shutdown()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("Server forced to shutdown: %v", err)
	}

	log.Println("Server exited")
}