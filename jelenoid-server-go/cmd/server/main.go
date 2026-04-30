package main

import (
	"context"
	"encoding/base64"
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
	"github.com/nats-io/nats.go"
)

func main() {
	cfg := config.Load()

	statusChan := make(chan struct{}, 256)
	sseHub := services.NewSSEHub()

	var natsConn *nats.Conn
	if cfg.NATSServer != "" {
		var err error
		natsConn, err = nats.Connect(cfg.NATSServer, nats.Timeout(2*time.Second))
		if err != nil {
			log.Printf("Failed to connect to NATS (%s): %v", cfg.NATSServer, err)
		} else {
			log.Println("Connected to NATS")
			js, err := natsConn.JetStream()
			if err == nil {
				streamInfo, err := js.StreamInfo("SESSIONS")
				if err != nil || streamInfo == nil {
					_, err = js.AddStream(&nats.StreamConfig{
						Name:     "SESSIONS",
						Subjects: []string{"sessions.*"},
						Storage:  nats.FileStorage,
						Replicas: 1,
					})
					if err != nil {
						log.Printf("JetStream not available: %v", err)
					} else {
						log.Println("Created JetStream stream 'SESSIONS'")
					}
				}
			}
		}
	}
	defer func() {
		if natsConn != nil {
			natsConn.Close()
		}
	}()

	sessionPublisher := services.NewSessionPublisher(natsConn)
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
		sessionPublisher,
		statusChan,
	)
	seleniumService := services.NewSeleniumSessionService(
		activeSessions,
		browserManager,
		dockerService,
		sessionPublisher,
		statusChan,
		cfg.PublicHost,
		cfg.ServerPort,
		cfg.AuthToken,
		cfg.PublicHost,
	)
	activeSessions.SetSeleniumService(seleniumService)

	statusService := services.NewStatusService(activeSessions)
	statusNotifier := services.NewStatusNotifier(sseHub, statusService)

	playwrightService := services.NewPlaywrightSessionService(
		activeSessions,
		dockerService,
		browserManager,
		sessionPublisher,
		statusChan,
		cfg.SessionTimeoutMs,
		cfg.AuthToken,
	)

	wdHubHandler := handlers.NewWdHubHandler(seleniumService)
	activeSessionsHandler := handlers.NewActiveSessionsHandler(activeSessions)
	browserManagerHandler := handlers.NewBrowserManagerHandler(browserManager)
	eventsHandler := handlers.NewEventsHandler(sseHub)
	logsHandler := handlers.NewLogsHandler(seleniumService)
	devToolsHandler := handlers.NewDevToolsProxyHandler(activeSessions)
	vncHandler := handlers.NewVncProxyHandler(activeSessions)

	mux := http.NewServeMux()

	mux.HandleFunc("/wd/hub/session", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodPost:
			wdHubHandler.CreateSession(w, r)
		case http.MethodDelete:
			wdHubHandler.DeleteSession(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})

	mux.HandleFunc("/wd/hub/session/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		pathAfterSession := strings.TrimPrefix(path, "/wd/hub/session/")

		if strings.HasSuffix(path, "/se/file") || strings.HasSuffix(path, "/file") {
			if r.Method == http.MethodPost {
				wdHubHandler.UploadFile(w, r)
				return
			}
		}

		if r.Method == http.MethodDelete {
			parts := strings.Split(pathAfterSession, "/")
			if len(parts) == 1 && parts[0] != "" {
				wdHubHandler.DeleteSession(w, r)
				return
			}
		}
		wdHubHandler.ProxyRequest(w, r)
	})

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
	mux.HandleFunc("/playwright-", func(w http.ResponseWriter, r *http.Request) {
		playwrightService.ServeHTTP(w, r)
	})

	handler := handlers.CORSMiddleware(cfg.UIHosts, mux)
	handler = handlers.LoggingMiddleware(handler)

	go func() {
		for range statusChan {
			statusNotifier.OnStatusChanged()
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

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("Server forced to shutdown: %v", err)
	}

	log.Println("Server exited")
}

func init() {
	_ = base64.StdEncoding
}