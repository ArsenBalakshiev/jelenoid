package services

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

type BrowserManagerService struct {
	mu               sync.Mutex
	browserList      map[string]*dto.BrowserInfo
	defaultBrowsers map[string]*dto.BrowserInfo
	configDir        string
}

func NewBrowserManagerService(configDir string) *BrowserManagerService {
	svc := &BrowserManagerService{
		browserList:      make(map[string]*dto.BrowserInfo),
		defaultBrowsers: make(map[string]*dto.BrowserInfo),
		configDir:        configDir,
	}
	svc.initBrowsersFromFile()
	return svc
}

func (s *BrowserManagerService) initBrowsersFromFile() {
	filePath := s.configDir

	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		dir := filepath.Dir(filePath)
		if err := os.MkdirAll(dir, 0755); err != nil {
			fmt.Printf("Failed to create directory: %v\n", err)
			return
		}
		if err := os.WriteFile(filePath, []byte("{}"), 0644); err != nil {
			fmt.Printf("Failed to create browsers.json: %v\n", err)
			return
		}
		fmt.Printf("Created new browsers.json at: %s\n", filePath)
	}

	browsers, err := s.readBrowsersFromFile()
	if err != nil {
		fmt.Printf("Error reading browsers file: %v\n", err)
		return
	}
	for _, b := range browsers {
		s.addBrowserClearly(b)
	}
}

func browserKey(name, version string) string {
	return name + ":" + version
}

func (s *BrowserManagerService) setDefaultBrowser(info *dto.BrowserInfo) {
	s.defaultBrowsers[info.Name] = info
}

func (s *BrowserManagerService) addBrowserClearly(info *dto.BrowserInfo) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	if info.IsDefault {
		s.setDefaultBrowser(info)
	}
	key := browserKey(info.Name, info.Version)
	existing, ok := s.browserList[key]
	if ok {
		return existing
	}
	s.browserList[key] = info
	return info
}

func (s *BrowserManagerService) AddBrowser(info *dto.BrowserInfo) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	if info.IsDefault {
		s.setDefaultBrowser(info)
	}
	key := browserKey(info.Name, info.Version)
	s.browserList[key] = info
	s.writeBrowsersToFile()
	return info
}

func (s *BrowserManagerService) DeleteBrowser(browserName, browserVersion string) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := browserKey(browserName, browserVersion)
	delete(s.defaultBrowsers, key)
	result := s.browserList[key]
	delete(s.browserList, key)
	s.writeBrowsersToFile()
	return result
}

func (s *BrowserManagerService) GetAllBrowsers() []*dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]*dto.BrowserInfo, 0, len(s.browserList))
	for _, b := range s.browserList {
		result = append(result, b)
	}
	return result
}

func (s *BrowserManagerService) GetImageByBrowserNameAndVersion(browserName, version string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if version == "" {
		if b, ok := s.defaultBrowsers[browserName]; ok {
			return b.DockerImageName
		}
		return ""
	}
	if b, ok := s.browserList[browserKey(browserName, version)]; ok {
		return b.DockerImageName
	}
	return ""
}

func (s *BrowserManagerService) GetBrowserInfoByBrowserNameAndVersion(browserName, version string) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	if version == "" {
		return s.defaultBrowsers[browserName]
	}
	return s.browserList[browserKey(browserName, version)]
}

func (s *BrowserManagerService) readBrowsersFromFile() ([]*dto.BrowserInfo, error) {
	data, err := os.ReadFile(s.configDir)
	if err != nil {
		return nil, err
	}

	var config dto.BrowsersConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}

	var result []*dto.BrowserInfo
	for name, entry := range config {
		for version, vi := range entry.Versions {
			isDefault := version == entry.Default
			result = append(result, &dto.BrowserInfo{
				Name:            name,
				Version:         version,
				DockerImageName: vi.Image,
				IsDefault:       isDefault,
			})
		}
	}
	return result, nil
}

func (s *BrowserManagerService) writeBrowsersToFile() {
	data := make(map[string]interface{})
	for _, b := range s.browserList {
		entry, ok := data[b.Name].(map[string]interface{})
		if !ok {
			entry = map[string]interface{}{
				"default":  nil,
				"versions": map[string]interface{}{},
			}
			data[b.Name] = entry
		}
		versions := entry["versions"].(map[string]interface{})
		versions[b.Version] = map[string]string{"image": b.DockerImageName}
		if b.IsDefault {
			entry["default"] = b.Version
		}
	}

	for _, entry := range data {
		e := entry.(map[string]interface{})
		if e["default"] == nil {
			versions := e["versions"].(map[string]interface{})
			for k := range versions {
				e["default"] = k
				break
			}
		}
	}

	jsonData, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		fmt.Printf("Error marshalling browsers: %v\n", err)
		return
	}
	if err := os.WriteFile(s.configDir, jsonData, 0644); err != nil {
		fmt.Printf("Error writing browsers file: %v\n", err)
	}
}