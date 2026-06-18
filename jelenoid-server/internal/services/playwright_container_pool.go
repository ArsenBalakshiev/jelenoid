package services

import (
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/balakshievas/jelenoid-server-go/internal/dto"
)

type poolContainerState int32

const (
	poolStateStarting poolContainerState = iota
	poolStateReady
	poolStateDraining
	poolStateStopped
)

type poolEntry struct {
	containerInfo *dto.ContainerInfo
	image         string
	version       string

	refCount atomic.Int32
	state    atomic.Int32

	createdAt time.Time
	idleSince atomic.Int64

	stopOnce  sync.Once
	idleTimer *time.Timer
}

func (e *poolEntry) State() poolContainerState {
	return poolContainerState(e.state.Load())
}

func (e *poolEntry) setState(s poolContainerState) {
	e.state.Store(int32(s))
}

func (e *poolEntry) MarkIdle() {
	e.idleSince.Store(time.Now().UnixMilli())
}

func (e *poolEntry) MarkActive() {
	e.idleSince.Store(0)
}

func (e *poolEntry) GetIdleSince() int64 {
	return e.idleSince.Load()
}

var (
	ErrPoolExhausted = errors.New("playwright container pool exhausted")
	ErrPoolClosed    = errors.New("playwright container pool is closed")
)

type PlaywrightContainerPool struct {
	dockerService *DockerExternalService

	enabled     bool
	idleTimeout time.Duration
	maxSize     int
	maxPerKey   int

	totalSlots chan struct{}

	mu      sync.Mutex
	entries map[string][]*poolEntry
	closed  bool

	stopIdleLoop chan struct{}
}

type PlaywrightPoolConfig struct {
	Enabled   bool
	IdleMs    int64
	MaxSize   int
	MaxPerKey int
}

func NewPlaywrightContainerPool(dockerService *DockerExternalService, cfg PlaywrightPoolConfig) *PlaywrightContainerPool {
	maxSize := cfg.MaxSize
	if maxSize <= 0 {
		maxSize = 10
	}
	maxPerKey := cfg.MaxPerKey
	if maxPerKey <= 0 {
		maxPerKey = 5
	}
	idleMs := cfg.IdleMs
	if idleMs <= 0 {
		idleMs = 60000
	}

	p := &PlaywrightContainerPool{
		dockerService: dockerService,
		enabled:       cfg.Enabled,
		idleTimeout:   time.Duration(idleMs) * time.Millisecond,
		maxSize:       maxSize,
		maxPerKey:     maxPerKey,
		totalSlots:    make(chan struct{}, maxSize),
		entries:       make(map[string][]*poolEntry),
		stopIdleLoop:  make(chan struct{}),
	}

	if p.enabled {
		go p.idleEvictionLoop()
	}

	return p
}

func (p *PlaywrightContainerPool) Enabled() bool {
	return p.enabled
}

func poolKey(image, version string) string {
	return image + "|" + version
}

func (p *PlaywrightContainerPool) totalCount() int {
	total := 0
	for _, list := range p.entries {
		total += len(list)
	}
	return total
}

func (p *PlaywrightContainerPool) Acquire(image, version string) (*poolEntry, error) {
	if !p.enabled {
		return nil, ErrPoolClosed
	}

	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, ErrPoolClosed
	}
	key := poolKey(image, version)

	for _, e := range p.entries[key] {
		if e.State() == poolStateReady && e.refCount.Load() == 0 {
			e.refCount.Add(1)
			e.MarkActive()
			if e.idleTimer != nil {
				e.idleTimer.Stop()
			}
			p.mu.Unlock()
			return e, nil
		}
	}

	if len(p.entries[key]) >= p.maxPerKey {
		existing := p.entries[key]
		var picked *poolEntry
		for _, e := range existing {
			if e.State() == poolStateReady {
				picked = e
				break
			}
		}
		if picked != nil {
			picked.refCount.Add(1)
			picked.MarkActive()
			if picked.idleTimer != nil {
				picked.idleTimer.Stop()
			}
			p.mu.Unlock()
			return picked, nil
		}
		p.mu.Unlock()
		return nil, ErrPoolExhausted
	}

	if p.totalCount() >= p.maxSize {
		p.mu.Unlock()
		return nil, ErrPoolExhausted
	}

	select {
	case p.totalSlots <- struct{}{}:
	default:
		p.mu.Unlock()
		return nil, ErrPoolExhausted
	}

	entry := &poolEntry{
		image:     image,
		version:   version,
		createdAt: time.Now(),
	}
	entry.setState(poolStateStarting)
	entry.refCount.Add(1)
	p.entries[key] = append(p.entries[key], entry)
	p.mu.Unlock()

	go p.startEntry(entry, key)

	return entry, nil
}

func (p *PlaywrightContainerPool) startEntry(entry *poolEntry, key string) {
	info, err := p.dockerService.StartPlaywrightContainer(entry.image, entry.version)
	if err != nil {
		p.removeAndReleaseSlot(entry, key)
		return
	}

	entry.containerInfo = info
	entry.MarkActive()
	entry.setState(poolStateReady)
}

func (p *PlaywrightContainerPool) removeAndReleaseSlot(entry *poolEntry, key string) {
	p.mu.Lock()
	list := p.entries[key]
	for i, e := range list {
		if e == entry {
			p.entries[key] = append(list[:i], list[i+1:]...)
			break
		}
	}
	p.mu.Unlock()

	entry.setState(poolStateStopped)
	entry.refCount.Store(0)
	<-p.totalSlots
}

func (p *PlaywrightContainerPool) Release(entry *poolEntry) {
	if entry == nil {
		return
	}

	entry.refCount.Add(-1)
	if entry.refCount.Load() > 0 {
		return
	}

	entry.MarkIdle()

	if !p.enabled {
		p.evict(entry)
		return
	}

	entry.idleTimer = time.AfterFunc(p.idleTimeout, func() {
		if entry.refCount.Load() == 0 && entry.State() == poolStateReady {
			p.evict(entry)
		}
	})
}

func (p *PlaywrightContainerPool) evict(entry *poolEntry) {
	entry.stopOnce.Do(func() {
		if entry.idleTimer != nil {
			entry.idleTimer.Stop()
		}
		entry.setState(poolStateDraining)

		key := poolKey(entry.image, entry.version)
		p.mu.Lock()
		list := p.entries[key]
		for i, e := range list {
			if e == entry {
				p.entries[key] = append(list[:i], list[i+1:]...)
				break
			}
		}
		p.mu.Unlock()

		if entry.containerInfo != nil {
			go p.dockerService.StopContainer(entry.containerInfo.ContainerID)
		}
		<-p.totalSlots
		entry.setState(poolStateStopped)
	})
}

func (p *PlaywrightContainerPool) idleEvictionLoop() {
	ticker := time.NewTicker(p.idleTimeout / 2)
	defer ticker.Stop()
	for {
		select {
		case <-p.stopIdleLoop:
			return
		case <-ticker.C:
			p.evictIdle()
		}
	}
}

func (p *PlaywrightContainerPool) evictIdle() {
	p.mu.Lock()
	var candidates []*poolEntry
	for _, list := range p.entries {
		for _, e := range list {
			if e.State() == poolStateReady && e.refCount.Load() == 0 {
				idleSince := e.GetIdleSince()
				if idleSince > 0 && time.Now().UnixMilli()-idleSince > p.idleTimeout.Milliseconds() {
					candidates = append(candidates, e)
				}
			}
		}
	}
	p.mu.Unlock()

	for _, e := range candidates {
		p.evict(e)
	}
}

func (p *PlaywrightContainerPool) StopAll() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	all := make([]*poolEntry, 0)
	for _, list := range p.entries {
		all = append(all, list...)
	}
	p.entries = make(map[string][]*poolEntry)
	p.mu.Unlock()

	close(p.stopIdleLoop)

	for _, e := range all {
		if e.idleTimer != nil {
			e.idleTimer.Stop()
		}
		if e.containerInfo != nil {
			go p.dockerService.StopContainer(e.containerInfo.ContainerID)
		}
		<-p.totalSlots
	}
}

func (p *PlaywrightContainerPool) Stats() PlaywrightPoolStats {
	p.mu.Lock()
	defer p.mu.Unlock()

	stats := PlaywrightPoolStats{
		Enabled: p.enabled,
		MaxSize: p.maxSize,
		Total:   0,
		ByKey:   make(map[string]PlaywrightPoolKeyStats),
	}

	for key, list := range p.entries {
		ks := PlaywrightPoolKeyStats{}
		for _, e := range list {
			stats.Total++
			switch e.State() {
			case poolStateStarting:
				ks.Starting++
			case poolStateReady:
				ks.Ready++
				if e.refCount.Load() > 0 {
					ks.InUse++
				}
			case poolStateDraining:
				ks.Draining++
			}
		}
		stats.ByKey[key] = ks
	}
	return stats
}

type PlaywrightPoolKeyStats struct {
	Starting int `json:"starting"`
	Ready    int `json:"ready"`
	InUse    int `json:"inUse"`
	Draining int `json:"draining"`
}

type PlaywrightPoolStats struct {
	Enabled bool                              `json:"enabled"`
	MaxSize int                               `json:"maxSize"`
	Total   int                               `json:"total"`
	ByKey   map[string]PlaywrightPoolKeyStats `json:"byKey"`
}
