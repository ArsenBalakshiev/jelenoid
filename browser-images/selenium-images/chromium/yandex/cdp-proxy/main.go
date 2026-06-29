package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

const pagePathPrefix = "/devtools/page/"

type target struct {
	ID              string `json:"id"`
	Type            string `json:"type"`
	WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
}

func findPageTarget(debuggerBase string) (string, error) {
	listURL := "http://" + debuggerBase + "/json/list"
	resp, err := http.Get(listURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	var targets []target
	if err := json.Unmarshal(body, &targets); err != nil {
		return "", err
	}
	for _, t := range targets {
		if t.Type == "page" {
			return t.WebSocketDebuggerURL, nil
		}
	}
	return "", nil
}

func main() {
	listen := os.Getenv("CDP_PROXY_LISTEN")
	if listen == "" {
		listen = "0.0.0.0:7070"
	}
	debuggerBase := os.Getenv("CDP_PROXY_DEBUGGER_BASE")
	if debuggerBase == "" {
		debuggerBase = "127.0.0.1:9222"
	}

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, pagePathPrefix) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}

		effectiveDebuggerBase := debuggerBase
		if addr := r.URL.Query().Get("debuggerAddress"); addr != "" {
			effectiveDebuggerBase = addr
		}

		clientConn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer clientConn.Close()

		backendURL, err := findPageTarget(effectiveDebuggerBase)
		if err != nil {
			log.Printf("cdp-proxy: failed to list targets: %v", err)
			return
		}
		if backendURL == "" {
			log.Printf("cdp-proxy: no page target found")
			return
		}

		backendConn, resp, err := websocket.DefaultDialer.Dial(backendURL, nil)
		if err != nil {
			log.Printf("cdp-proxy: dial %s failed: %v", backendURL, err)
			if resp != nil && resp.Body != nil {
				resp.Body.Close()
			}
			return
		}
		defer backendConn.Close()

		errChan := make(chan error, 2)

		go func() {
			for {
				mt, data, err := clientConn.ReadMessage()
				if err != nil {
					errChan <- err
					return
				}
				if err := backendConn.WriteMessage(mt, data); err != nil {
					errChan <- err
					return
				}
			}
		}()

		go func() {
			for {
				mt, data, err := backendConn.ReadMessage()
				if err != nil {
					errChan <- err
					return
				}
				if err := clientConn.WriteMessage(mt, data); err != nil {
					errChan <- err
					return
				}
			}
		}()

		<-errChan
	})

	log.Printf("cdp-proxy listening on %s -> %s", listen, debuggerBase)
	log.Fatal(http.ListenAndServe(listen, nil))
}
