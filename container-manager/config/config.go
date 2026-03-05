package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	Port                string
	DockerNetwork       string
	PlaywrightPort      string
	PlaywrightShmSize   int64
	PlaywrightTmpfsSize string
	SeleniumShmSize     int64
	SeleniumTmpfsSize   string
	CleanupTimeout      time.Duration
	StartingTimeout     time.Duration
}

func LoadConfig() *Config {
	return &Config{
		Port:                getEnv("CONTAINER_MANAGER_PORT", "8080"),
		DockerNetwork:       getEnv("DOCKER_NETWORK", "jelenoid-net"),
		PlaywrightPort:      getEnv("PLAYWRIGHT_PORT", "3000"),
		PlaywrightShmSize:   getEnvInt64("PLAYWRIGHT_CONTAINER_SHM_SIZE", 4294967296),
		PlaywrightTmpfsSize: getEnv("PLAYWRIGHT_CONTAINER_TMPFS_SIZE", "2g"),
		SeleniumShmSize:     getEnvInt64("SELENIUM_CONTAINER_SHM_SIZE", 2147483648),
		SeleniumTmpfsSize:   getEnv("SELENIUM_CONTAINER_TMPFS_SIZE", "1g"),
		CleanupTimeout:      time.Duration(getEnvInt64("CLEANUP_TIMEOUT", 15000)) * time.Millisecond,
		StartingTimeout:     time.Duration(getEnvInt64("CONTAINER_STARTING_TIMEOUT", 60000)) * time.Millisecond,
	}
}

func getEnv(key, fallback string) string {
	if value, exists := os.LookupEnv(key); exists {
		return value
	}
	return fallback
}

func getEnvInt64(key string, fallback int64) int64 {
	strValue := getEnv(key, "")
	if value, err := strconv.ParseInt(strValue, 10, 64); err == nil {
		return value
	}
	return fallback
}
