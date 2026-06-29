package main

import (
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
)

func main() {
	listen := os.Getenv("WD_PROXY_LISTEN")
	if listen == "" {
		listen = "0.0.0.0:4444"
	}
	backend := os.Getenv("WD_PROXY_BACKEND")
	if backend == "" {
		backend = "http://127.0.0.1:4445"
	}

	backendURL, err := url.Parse(backend)
	if err != nil {
		log.Fatalf("invalid backend URL %s: %v", backend, err)
	}

	proxy := httputil.NewSingleHostReverseProxy(backendURL)
	proxy.ErrorLog = log.Default()

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// Старые версии container-manager обращаются к /wd/hub/status,
		// а chromedriver использует URL base / по умолчанию.
		// Поддерживаем оба варианта, убирая префикс /wd/hub.
		r.URL.Path = strings.TrimPrefix(r.URL.Path, "/wd/hub")
		if r.URL.Path == "" {
			r.URL.Path = "/"
		}
		// Chromedriver валидирует Host/Origin заголовок и отклоняет запросы,
		// пришедшие с внешних имён контейнеров. Подменяем Host на backend.
		r.Host = backendURL.Host
		proxy.ServeHTTP(w, r)
	})

	log.Printf("wd-proxy listening on %s -> %s", listen, backend)
	log.Fatal(http.ListenAndServe(listen, nil))
}
