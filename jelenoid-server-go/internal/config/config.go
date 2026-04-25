package config

import (
	"os"
	"strconv"
)

type Config struct {
	ServerPort           int
	PublicHost           string
	AuthToken            string
	ContainerManagerAddr string
	SessionLimit         int
	QueueLimit           int
	BrowsersConfigDir    string
	QueueTimeoutMs       int64
	SessionTimeoutMs    int64
	StartupTimeoutMs     int64
	UIHosts             []string
	PlaywrightMaxSessions int
	PlaywrightQueueLimit  int
	NATSServer           string
}

func Load() *Config {
	return &Config{
		ServerPort:           getEnvInt("JELENOID_PORT", 4444),
		PublicHost:           getEnvStr("JELENOID_PUBLIC_HOST", "0.0.0.0"),
		AuthToken:            getEnvStr("JELENOID_AUTH_TOKEN", ""),
		ContainerManagerAddr: getEnvStr("CONTAINER_MANAGER_ADDRESS", "http://container-manager:8080"),
		SessionLimit:         getEnvInt("PARALLEL_SESSIONS", 10),
		QueueLimit:           getEnvInt("QUEUE_LIMIT", 100),
		BrowsersConfigDir:    getEnvStr("BROWSERS_FILE", "browsers.json"),
		QueueTimeoutMs:       getEnvInt64("QUEUE_TIMEOUT", 30000),
		SessionTimeoutMs:     getEnvInt64("SESSION_TIMEOUT", 600000),
		StartupTimeoutMs:     getEnvInt64("STARTUP_TIMEOUT", 30000),
		UIHosts:             getEnvSlice("UI_HOSTS_LIST", []string{"http://localhost:3000", "http://localhost:4444"}),
		PlaywrightMaxSessions: getEnvInt("PLAYWRIGHT_SESSION_LIMIT", 10),
		PlaywrightQueueLimit:  getEnvInt("PLAYWRIGHT_QUEUE_LIMIT", 100),
		NATSServer:           getEnvStr("NATS_SERVER", ""),
	}
}

func getEnvStr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return fallback
}

func getEnvInt64(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.ParseInt(v, 10, 64); err == nil {
			return i
		}
	}
	return fallback
}

func getEnvSlice(key string, fallback []string) []string {
	if v := os.Getenv(key); v != "" {
		return []string{v}
	}
	return fallback
}