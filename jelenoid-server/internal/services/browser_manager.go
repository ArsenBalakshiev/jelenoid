package services

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

type browserSnapshot struct {
	all       []*dto.BrowserInfo
	byKey     map[string]*dto.BrowserInfo
	byDefault map[string]*dto.BrowserInfo
}

type BrowserManagerService struct {
	configDir string
	snap      atomic.Pointer[browserSnapshot]

	mu        sync.Mutex
	dirty     bool
	persistCh chan struct{}
	quit      chan struct{}
}

const browserPersistDebounce = 500 * time.Millisecond

func NewBrowserManagerService(configDir string) *BrowserManagerService {
	s := &BrowserManagerService{
		configDir: configDir,
		persistCh: make(chan struct{}, 1),
		quit:      make(chan struct{}),
	}
	s.snap.Store(&browserSnapshot{
		all:       []*dto.BrowserInfo{},
		byKey:     map[string]*dto.BrowserInfo{},
		byDefault: map[string]*dto.BrowserInfo{},
	})
	s.initBrowsersFromFile()
	go s.persistLoop()
	return s
}

func (s *BrowserManagerService) Shutdown() {
	select {
	case <-s.quit:
		return
	default:
		close(s.quit)
	}
	s.persistNow()
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

	all := make([]*dto.BrowserInfo, 0, len(browsers))
	byKey := make(map[string]*dto.BrowserInfo, len(browsers))
	byDefault := make(map[string]*dto.BrowserInfo)
	for _, b := range browsers {
		all = append(all, b)
		byKey[browserKey(b.Name, b.Version)] = b
		if b.IsDefault {
			byDefault[b.Name] = b
		}
	}
	s.snap.Store(&browserSnapshot{all: all, byKey: byKey, byDefault: byDefault})
}

func browserKey(name, version string) string {
	return name + ":" + version
}

func (s *BrowserManagerService) AddBrowser(info *dto.BrowserInfo) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()

	current := s.snap.Load()
	key := browserKey(info.Name, info.Version)
	if existing, ok := current.byKey[key]; ok {
		return existing
	}

	newByKey := make(map[string]*dto.BrowserInfo, len(current.byKey)+1)
	for k, v := range current.byKey {
		newByKey[k] = v
	}
	newByKey[key] = info

	newByDefault := make(map[string]*dto.BrowserInfo, len(current.byDefault)+1)
	for k, v := range current.byDefault {
		if k != info.Name {
			newByDefault[k] = v
		}
	}
	if info.IsDefault {
		newByDefault[info.Name] = info
	}

	newAll := make([]*dto.BrowserInfo, 0, len(current.all)+1)
	newAll = append(newAll, current.all...)
	newAll = append(newAll, info)

	s.snap.Store(&browserSnapshot{all: newAll, byKey: newByKey, byDefault: newByDefault})
	s.markDirty()
	return info
}

func (s *BrowserManagerService) DeleteBrowser(browserName, browserVersion string) *dto.BrowserInfo {
	s.mu.Lock()
	defer s.mu.Unlock()

	current := s.snap.Load()
	key := browserKey(browserName, browserVersion)
	existing, ok := current.byKey[key]
	if !ok {
		return nil
	}

	newByKey := make(map[string]*dto.BrowserInfo, len(current.byKey)-1)
	for k, v := range current.byKey {
		if k != key {
			newByKey[k] = v
		}
	}

	newByDefault := make(map[string]*dto.BrowserInfo, len(current.byDefault))
	for k, v := range current.byDefault {
		if k != browserName {
			newByDefault[k] = v
		}
	}

	newAll := make([]*dto.BrowserInfo, 0, len(current.all)-1)
	for _, b := range current.all {
		if b != existing {
			newAll = append(newAll, b)
		}
	}

	s.snap.Store(&browserSnapshot{all: newAll, byKey: newByKey, byDefault: newByDefault})
	s.markDirty()
	return existing
}

func (s *BrowserManagerService) markDirty() {
	s.dirty = true
	select {
	case s.persistCh <- struct{}{}:
	default:
	}
}

func (s *BrowserManagerService) GetAllBrowsers() []*dto.BrowserInfo {
	snap := s.snap.Load()
	if snap == nil {
		return nil
	}
	return snap.all
}

func (s *BrowserManagerService) GetImageByBrowserNameAndVersion(browserName, version string) string {
	snap := s.snap.Load()
	if snap == nil {
		return ""
	}
	if version == "" {
		if b, ok := snap.byDefault[browserName]; ok {
			return b.DockerImageName
		}
		return ""
	}
	if b, ok := snap.byKey[browserKey(browserName, version)]; ok {
		return b.DockerImageName
	}
	return ""
}

func (s *BrowserManagerService) GetBrowserInfoByBrowserNameAndVersion(browserName, version string) *dto.BrowserInfo {
	snap := s.snap.Load()
	if snap == nil {
		return nil
	}
	if version == "" {
		return snap.byDefault[browserName]
	}
	return snap.byKey[browserKey(browserName, version)]
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
				WireBrowserName: vi.WireBrowserName,
			})
		}
	}
	return result, nil
}

func (s *BrowserManagerService) persistLoop() {
	for {
		select {
		case <-s.persistCh:
		case <-s.quit:
			return
		}
		for drained := false; !drained; {
			select {
			case <-s.persistCh:
			default:
				drained = true
			}
		}
		select {
		case <-time.After(browserPersistDebounce):
			s.persistNow()
		case <-s.quit:
			return
		}
	}
}

func (s *BrowserManagerService) persistNow() {
	s.mu.Lock()
	if !s.dirty {
		s.mu.Unlock()
		return
	}
	s.dirty = false
	snap := s.snap.Load()
	s.mu.Unlock()

	data := s.snapshotToJSON(snap)
	if data == nil {
		return
	}
	if err := s.atomicWrite(s.configDir, data); err != nil {
		fmt.Printf("Error writing browsers file: %v\n", err)
		s.mu.Lock()
		s.dirty = true
		s.mu.Unlock()
	}
}

func (s *BrowserManagerService) snapshotToJSON(snap *browserSnapshot) []byte {
	data := make(map[string]interface{})
	for _, b := range snap.all {
		entry, ok := data[b.Name].(map[string]interface{})
		if !ok {
			entry = map[string]interface{}{
				"default":  nil,
				"versions": map[string]interface{}{},
			}
			data[b.Name] = entry
		}
		versions := entry["versions"].(map[string]interface{})
		versionEntry := map[string]string{"image": b.DockerImageName}
		if b.WireBrowserName != "" {
			versionEntry["wireBrowserName"] = b.WireBrowserName
		}
		versions[b.Version] = versionEntry
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
		return nil
	}
	return jsonData
}

func (s *BrowserManagerService) atomicWrite(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
