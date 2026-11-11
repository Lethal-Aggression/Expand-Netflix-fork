package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
)

// One file per piece, with space reserved before the first chunk is accepted.
// Promotion to complete is an in-memory state change, not a second disk copy.
// The torrent client revalidates completion when rolling eviction removes data.
type diskCache struct {
	mu                 sync.Mutex
	root               string
	capacity, reserved int64
	entries            map[string]*diskEntry
}
type diskEntry struct {
	length   int64
	complete bool
	accessed time.Time
}
type diskInfo struct {
	Filled, Capacity int64
	NumItems         int
}
type diskItemInfo struct {
	Path     string
	Size     int64
	Accessed time.Time
}

func newDiskCache(root string, capacity int64) (*diskCache, error) {
	root = filepath.Join(root, "pieces-v3")
	if err := os.MkdirAll(root, 0700); err != nil {
		return nil, err
	}
	// Incomplete piece state cannot survive a process restart. Never trust stale chunks.
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".piece") {
			if err = os.Remove(filepath.Join(root, e.Name())); err != nil {
				return nil, err
			}
		}
	}
	return &diskCache{root: root, capacity: capacity, entries: make(map[string]*diskEntry)}, nil
}
func (c *diskCache) SetCapacity(n int64) { c.mu.Lock(); defer c.mu.Unlock(); c.capacity = n }
func (c *diskCache) Info() diskInfo {
	c.mu.Lock()
	defer c.mu.Unlock()
	return diskInfo{c.reserved, c.capacity, len(c.entries)}
}
func (c *diskCache) WalkItems(fn func(diskItemInfo)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for key, e := range c.entries {
		fn(diskItemInfo{key, e.length, e.accessed})
	}
}
func (c *diskCache) Remove(key string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.removeLocked(key)
}
func (c *diskCache) removeLocked(key string) error {
	e, ok := c.entries[key]
	if !ok {
		return nil
	}
	err := os.Remove(filepath.Join(c.root, key+".piece"))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	delete(c.entries, key)
	c.reserved -= e.length
	return nil
}
func (c *diskCache) TrimToCapacity() {} // Never evict arbitrary in-use/incomplete chunks.
func (c *diskCache) OpenTorrent(info *metainfo.Info, hash metainfo.Hash) (storage.TorrentImpl, error) {
	return storage.TorrentImpl{Piece: func(p metainfo.Piece) storage.PieceImpl {
		return &diskPiece{cache: c, key: fmt.Sprintf("%s-%d", hash.HexString(), p.Index()), length: p.Length()}
	}, Close: func() error { return nil }}, nil
}
func (c *diskCache) dropHash(hash string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for key := range c.entries {
		if strings.HasPrefix(key, hash+"-") {
			_ = c.removeLocked(key)
		}
	}
}

// Only evict completed pieces; a downloader is never writing these files.
func (c *diskCache) evict(hash string, begin, end, current, ahead, behind int) []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	var removed []int
	for key, e := range c.entries {
		if !e.complete || !strings.HasPrefix(key, hash+"-") {
			continue
		}
		var index int
		if _, err := fmt.Sscanf(strings.TrimPrefix(key, hash+"-"), "%d", &index); err != nil {
			continue
		}
		keep := index >= current-behind && index < current+ahead || index < begin+2 || index >= end-2
		if !keep {
			if c.removeLocked(key) == nil {
				removed = append(removed, index)
			}
		}
	}
	return removed
}

type diskPiece struct {
	cache  *diskCache
	key    string
	length int64
}

func (p *diskPiece) ReadAt(b []byte, offset int64) (int, error) {
	p.cache.mu.Lock()
	defer p.cache.mu.Unlock()
	e := p.cache.entries[p.key]
	if e == nil {
		return 0, os.ErrNotExist
	}
	e.accessed = time.Now()
	f, err := os.Open(filepath.Join(p.cache.root, p.key+".piece"))
	if err != nil {
		return 0, err
	}
	defer f.Close()
	return f.ReadAt(b, offset)
}
func (p *diskPiece) WriteAt(b []byte, offset int64) (int, error) {
	if offset < 0 || offset > p.length || int64(len(b)) > p.length-offset {
		return 0, io.ErrShortWrite
	}
	p.cache.mu.Lock()
	defer p.cache.mu.Unlock()
	e := p.cache.entries[p.key]
	if e == nil {
		if p.cache.reserved+p.length > p.cache.capacity {
			return 0, syscall.ENOSPC
		}
		e = &diskEntry{length: p.length}
		p.cache.entries[p.key] = e
		p.cache.reserved += p.length
	}
	f, err := os.OpenFile(filepath.Join(p.cache.root, p.key+".piece"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	e.accessed = time.Now()
	return f.WriteAt(b, offset)
}
func (p *diskPiece) MarkComplete() error {
	p.cache.mu.Lock()
	defer p.cache.mu.Unlock()
	e := p.cache.entries[p.key]
	if e == nil {
		return os.ErrNotExist
	}
	e.complete = true
	return nil
}
func (p *diskPiece) MarkNotComplete() error {
	p.cache.mu.Lock()
	defer p.cache.mu.Unlock()
	if e := p.cache.entries[p.key]; e != nil {
		e.complete = false
	}
	return nil
}
func (p *diskPiece) Completion() storage.Completion {
	p.cache.mu.Lock()
	defer p.cache.mu.Unlock()
	e := p.cache.entries[p.key]
	return storage.Completion{Complete: e != nil && e.complete, Ok: true}
}
