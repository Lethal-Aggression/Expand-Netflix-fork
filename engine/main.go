package main

import (
	"context"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	anacrolixLog "github.com/anacrolix/log"
	"github.com/anacrolix/missinggo/v2/filecache"
	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
	"golang.org/x/time/rate"
)

var fastMetaClient = &http.Client{
	Timeout: 4 * time.Second,
	Transport: &http.Transport{
		MaxIdleConns:        20,
		MaxIdleConnsPerHost: 10,
		IdleConnTimeout:     30 * time.Second,
		DisableCompression: false,
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true, // Bypass missing system root CA bundles on Android untrusted app sandbox
		},
		DialContext: (&net.Dialer{
			Timeout:   3 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
	},
}

func fetchFastMetainfo(ctx context.Context, h metainfo.Hash, t *torrent.Torrent) {
	if t.Info() != nil {
		return
	}
	hashUpper := strings.ToUpper(h.HexString())
	endpoints := []string{
		fmt.Sprintf("https://itorrents.net/torrent/%s.torrent", hashUpper),
		fmt.Sprintf("https://torrage.info/torrent.php?h=%s", hashUpper),
	}

	var wg sync.WaitGroup
	for _, ep := range endpoints {
		wg.Add(1)
		go func(endpoint string) {
			defer wg.Done()
			select {
			case <-ctx.Done():
				return
			default:
			}
			if t.Info() != nil {
				return
			}

			req, err := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
			if err != nil {
				return
			}
			req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
			req.Header.Set("Accept", "*/*")

			resp, err := fastMetaClient.Do(req)
			if err != nil {
				log.Printf("[JewEngine] FastMeta probe %s failed: %v", endpoint, err)
				return
			}
			defer resp.Body.Close()

			if resp.StatusCode == http.StatusOK {
				mi, err := metainfo.Load(resp.Body)
				if err != nil {
					log.Printf("[JewEngine] FastMeta decode failed for %s: %v", endpoint, err)
					return
				}
				if len(mi.InfoBytes) > 0 {
					if err := t.SetInfoBytes(mi.InfoBytes); err == nil {
						log.Printf("[JewEngine] Instant HTTPS metadata resolved via %s for %s! (name=%s)", endpoint, hashUpper, t.Name())
						return
					}
				}
				spec := torrent.TorrentSpecFromMetaInfo(mi)
				if err := t.MergeSpec(spec); err == nil {
					log.Printf("[JewEngine] Instant HTTPS metadata merged via %s for %s! (name=%s)", endpoint, hashUpper, t.Name())
					return
				}
			} else {
				log.Printf("[JewEngine] FastMeta status %d for %s", resp.StatusCode, endpoint)
			}
		}(ep)
	}
	wg.Wait()
}

var defaultTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.tracker.cl:1337/announce",
	"udp://tracker.torrent.eu.org:451/announce",
	"udp://explodie.org:6969/announce",
	"udp://open.stealth.si:80/announce",
	"udp://tracker.dler.org:6969/announce",
	"udp://tracker.openbittorrent.com:6969/announce",
	"udp://p4p.arenabg.com:1337/announce",
	"udp://tracker.moeking.me:6969/announce",
	"udp://movies.zsw.ca:6969/announce",
	"udp://new-line.net:6969/announce",
	"udp://exodus.desync.com:6969/announce",
	"udp://tracker.tiny-vps.com:6969/announce",
	"udp://tracker.theoks.net:6969/announce",
	"udp://tracker-udp.gbitt.info:80/announce",
	"udp://tracker1.bt.moack.co.kr:80/announce",
	"udp://bt1.archive.org:6969/announce",
	"udp://bt2.archive.org:6969/announce",
	"udp://retracker01-msk-virt.corbina.net:80/announce",
	"http://nyaa.tracker.wf:7777/announce",
	"http://anidex.moe:6969/announce",
	"http://tracker.openbittorrent.com:80/announce",
	"http://tracker.opentrackr.org:1337/announce",
	"https://tracker.tamersunion.org:443/announce",
}

var dnsResolvers = []string{
	"1.1.1.1:53",
	"8.8.8.8:53",
	"9.9.9.9:53",
}

type SpeedTracker struct {
	lastBytes int64
	lastTime  time.Time
	speed     float64
}

type Engine struct {
	client         *torrent.Client
	cache          *filecache.Cache
	cacheDir       string
	maxCacheBytes  atomic.Int64
	mu             sync.Mutex
	activeTorrents map[string]*torrent.Torrent
	speedTrackers  map[string]*SpeedTracker
	activeStreams  atomic.Int32
	prewarmHash    string
	prewarmCancel  context.CancelFunc
	dnsIndex       atomic.Uint32
}

func main() {
	port := flag.Int("port", 8811, "Port for JewEngine HTTP micro-server")
	cacheDir := flag.String("cacheDir", "./torrent_cache", "Directory for bounded rolling piece storage")
	maxCacheMB := flag.Int("maxCacheMB", 400, "Rolling FIFO disk cache limit in MB")
	flag.Parse()

	// Keep Go memory footprint compact for 32-bit 2GB Android TV devices
	debug.SetMemoryLimit(160 << 20)
	debug.SetGCPercent(50)

	capMB := *maxCacheMB
	if capMB < 200 {
		capMB = 200
	} else if capMB > 800 {
		capMB = 800
	}
	capBytes := int64(capMB) * 1024 * 1024

	// Android has no /etc/resolv.conf; rotate reliable public DNS servers
	var dnsSeq atomic.Uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 1800 * time.Millisecond}
			start := int(dnsSeq.Add(1))
			for i := 0; i < len(dnsResolvers); i++ {
				target := dnsResolvers[(start+i)%len(dnsResolvers)]
				conn, err := d.DialContext(ctx, "udp", target)
				if err == nil {
					return conn, nil
				}
			}
			return d.DialContext(ctx, "udp", "8.8.8.8:53")
		},
	}

	if err := os.MkdirAll(*cacheDir, 0755); err != nil {
		log.Fatalf("[JewEngine] Failed to create cache directory: %v", err)
	}

	fc, err := filecache.NewCache(*cacheDir)
	if err != nil {
		log.Fatalf("[JewEngine] Failed to initialize filecache: %v", err)
	}
	// CRITICAL: Disable filecache auto-trimming (-1) so incomplete chunk files are NEVER deleted mid-download!
	// Rolling eviction is handled exclusively by evictPastWatchedPieces on completed pieces behind playback head.
	fc.SetCapacity(-1)

	log.Printf("[JewEngine] Sovereign Engine v2.0 starting on 127.0.0.1:%d | Rolling FIFO Cache Cap: %d MB", *port, capMB)

	cfg := torrent.NewDefaultClientConfig()
	cfg.DefaultStorage = storage.NewResourcePiecesOpts(fc.AsResourceProvider(), storage.ResourcePiecesOpts{
		LeaveIncompleteChunks: false,
	})
	cfg.DisableIPv6 = true
	cfg.NoDefaultPortForwarding = false // Enable UPnP / NAT-PMP router hole punching
	cfg.DisableUTP = false             // Enable uTP for peer-to-peer UDP hole punching and NAT traversal
	cfg.DisableTCP = false
	cfg.HeaderObfuscationPolicy.Preferred = true
	cfg.NoDHT = false
	cfg.DropDuplicatePeerIds = true
	cfg.DropMutuallyCompletePeers = true
	cfg.HandshakesTimeout = 3500 * time.Millisecond
	cfg.NominalDialTimeout = 3500 * time.Millisecond
	cfg.MinDialTimeout = 2000 * time.Millisecond
	cfg.EstablishedConnsPerTorrent = 45 // Calibrated for Android TV Wi-Fi: prevents socket starvation
	cfg.HalfOpenConnsPerTorrent = 20
	cfg.TotalHalfOpenConns = 35
	cfg.TorrentPeersHighWater = 90
	cfg.TorrentPeersLowWater = 25
	cfg.PeriodicallyAnnounceTorrentsToDht = true
	// Burst upload cap to 4 MB/s so swarm reciprocal tit-for-tat grants maximum download line speed
	cfg.UploadRateLimiter = rate.NewLimiter(rate.Limit(4*1024*1024), 1024*1024)
	// Enable Info level torrent logger to record peer lifecycle, unchoking, and piece flow
	cfg.Logger = anacrolixLog.NewLogger("jew").WithFilterLevel(anacrolixLog.Info)

	client, err := torrent.NewClient(cfg)
	if err != nil {
		log.Fatalf("[JewEngine] Failed to create torrent client: %v", err)
	}
	defer client.Close()

	eng := &Engine{
		client:         client,
		cache:          fc,
		cacheDir:       *cacheDir,
		activeTorrents: make(map[string]*torrent.Torrent),
		speedTrackers:  make(map[string]*SpeedTracker),
	}
	eng.maxCacheBytes.Store(capBytes)

	mux := http.NewServeMux()
	mux.HandleFunc("/health", eng.handleHealth)
	mux.HandleFunc("/config", eng.handleConfig)
	mux.HandleFunc("/prewarm", eng.handlePrewarm)
	mux.HandleFunc("/stream", eng.handleStream)
	mux.HandleFunc("/stats", eng.handleStats)
	mux.HandleFunc("/drop", eng.handleDrop)
	mux.HandleFunc("/wipe", eng.handleWipe)
	mux.HandleFunc("/shutdown", eng.handleShutdown)

	server := &http.Server{
		Addr:         fmt.Sprintf("127.0.0.1:%d", *port),
		Handler:      corsMiddleware(mux),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 0, // Unlimited streaming duration for 4K cinema
		IdleTimeout:  60 * time.Second,
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigChan
		log.Println("[JewEngine] Termination signal received, shutting down...")
		server.Close()
	}()

	log.Printf("[JewEngine] Ready on http://127.0.0.1:%d", *port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("[JewEngine] Server error: %v", err)
	}
	log.Println("[JewEngine] Shutdown complete.")
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(payload)
}

func (e *Engine) handleHealth(w http.ResponseWriter, r *http.Request) {
	info := e.cache.Info()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":          "ok",
		"engine":          "jewflix-engine",
		"version":         "2.0",
		"cache_bytes":     info.Filled,
		"cache_cap_bytes": e.maxCacheBytes.Load(),
		"cache_items":     info.NumItems,
	})
}

func (e *Engine) handleConfig(w http.ResponseWriter, r *http.Request) {
	mbStr := strings.TrimSpace(r.URL.Query().Get("maxCacheMB"))
	if mbStr != "" {
		if mb, err := strconv.Atoi(mbStr); err == nil {
			if mb < 200 {
				mb = 200
			} else if mb > 800 {
				mb = 800
			}
			capBytes := int64(mb) * 1024 * 1024
			e.maxCacheBytes.Store(capBytes)
			log.Printf("[JewEngine] Updated rolling FIFO cache limit -> %d MB", mb)
		}
	}
	info := e.cache.Info()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":          "ok",
		"cache_bytes":     info.Filled,
		"cache_cap_bytes": e.maxCacheBytes.Load(),
	})
}

func (e *Engine) handleShutdown(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "shutting_down"})
	go func() {
		time.Sleep(300 * time.Millisecond)
		os.Exit(0)
	}()
}

func (e *Engine) handleWipe(w http.ResponseWriter, r *http.Request) {
	log.Println("[JewEngine] Executing /wipe: dropping torrents and purging LRU filecache...")
	e.mu.Lock()
	if e.prewarmCancel != nil {
		e.prewarmCancel()
		e.prewarmCancel = nil
		e.prewarmHash = ""
	}
	for _, t := range e.client.Torrents() {
		t.Drop()
	}
	e.activeTorrents = make(map[string]*torrent.Torrent)
	e.speedTrackers = make(map[string]*SpeedTracker)
	e.mu.Unlock()

	time.Sleep(80 * time.Millisecond)

	// Remove every cached entry via filecache API so filled/items/policy remain 100% coherent
	var keys []string
	e.cache.WalkItems(func(item filecache.ItemInfo) {
		keys = append(keys, string(item.Path))
	})
	for _, k := range keys {
		_ = e.cache.Remove(k)
	}
	e.cache.TrimToCapacity()

	// Remove any empty stray subdirectories without deleting root
	if entries, err := os.ReadDir(e.cacheDir); err == nil {
		for _, ent := range entries {
			_ = os.RemoveAll(filepath.Join(e.cacheDir, ent.Name()))
		}
	}

	info := e.cache.Info()
	log.Printf("[JewEngine] Wipe complete (items=%d, bytes=%d)", info.NumItems, info.Filled)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":      "wiped",
		"cache_bytes": info.Filled,
	})
}

func (e *Engine) handleDrop(w http.ResponseWriter, r *http.Request) {
	hashStr := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("hash")))
	if hashStr == "" {
		http.Error(w, "Missing hash parameter", http.StatusBadRequest)
		return
	}
	var h metainfo.Hash
	if err := h.FromHexString(hashStr); err != nil {
		http.Error(w, "Invalid hex hash: "+err.Error(), http.StatusBadRequest)
		return
	}
	t, ok := e.client.Torrent(h)
	if ok {
		t.Drop()
		e.mu.Lock()
		delete(e.activeTorrents, hashStr)
		delete(e.speedTrackers, hashStr)
		if e.prewarmHash == hashStr && e.prewarmCancel != nil {
			e.prewarmCancel()
			e.prewarmCancel = nil
			e.prewarmHash = ""
		}
		e.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]string{"status": "dropped"})
	} else {
		writeJSON(w, http.StatusOK, map[string]string{"status": "not_found"})
	}
}

func cleanTrackerUrl(raw string) string {
	raw = strings.TrimSpace(raw)
	raw = strings.TrimPrefix(raw, "tracker:")
	if strings.HasPrefix(raw, "dht:") {
		return ""
	}
	if strings.HasPrefix(raw, "udp://") || strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://") {
		return raw
	}
	return ""
}

func (e *Engine) addTrackers(t *torrent.Torrent, r *http.Request) {
	seen := make(map[string]bool)
	var announceList [][]string
	for _, tr := range defaultTrackers {
		if c := cleanTrackerUrl(tr); c != "" && !seen[c] {
			seen[c] = true
			announceList = append(announceList, []string{c})
		}
	}
	if trs, ok := r.URL.Query()["tr"]; ok {
		for _, tr := range trs {
			if c := cleanTrackerUrl(tr); c != "" && !seen[c] {
				seen[c] = true
				announceList = append(announceList, []string{c})
			}
		}
	}
	t.AddTrackers(announceList)
}

func selectTargetFile(files []*torrent.File, idxStr string) *torrent.File {
	if len(files) == 0 {
		return nil
	}
	if idxStr != "" {
		if idx, err := strconv.Atoi(idxStr); err == nil && idx >= 0 && idx < len(files) {
			return files[idx]
		}
	}
	var largest *torrent.File
	for _, f := range files {
		lower := strings.ToLower(f.DisplayPath())
		if strings.HasSuffix(lower, ".srt") || strings.HasSuffix(lower, ".sub") ||
			strings.HasSuffix(lower, ".txt") || strings.HasSuffix(lower, ".nfo") ||
			strings.HasSuffix(lower, ".jpg") || strings.HasSuffix(lower, ".png") {
			continue
		}
		if largest == nil || f.Length() > largest.Length() {
			largest = f
		}
	}
	if largest == nil {
		for _, f := range files {
			if largest == nil || f.Length() > largest.Length() {
				largest = f
			}
		}
	}
	return largest
}

func warmContainerBoundaries(t *torrent.Torrent, target *torrent.File) {
	if target == nil || target.Length() == 0 || t.Info() == nil {
		return
	}
	begin := target.BeginPieceIndex()
	end := target.EndPieceIndex()
	numPieces := t.NumPieces()
	if end > numPieces {
		end = numPieces
	}
	if begin >= end {
		return
	}

	// 1. Immediately burst-download the first 4 pieces (head containing EBML / MP4 ftyp / moov)
	headEnd := begin + 4
	if headEnd > end {
		headEnd = end
	}
	for p := begin; p < headEnd; p++ {
		t.Piece(p).SetPriority(torrent.PiecePriorityNow)
	}
	t.DownloadPieces(begin, headEnd)

	// 2. Immediately burst-download the trailing 3 pieces (footer containing trailing moov atom or MKV Cues)
	// This ELIMINATES the 20-second freeze on movies with trailing metadata!
	ext := strings.ToLower(filepath.Ext(target.DisplayPath()))
	if (ext == ".mp4" || ext == ".m4v" || ext == ".mkv" || ext == ".webm") && end-begin > 4 {
		footStart := end - 3
		if footStart < headEnd {
			footStart = headEnd
		}
		for p := footStart; p < end; p++ {
			t.Piece(p).SetPriority(torrent.PiecePriorityNow)
		}
		t.DownloadPieces(footStart, end)
	}
}

func applySlidingWindowPriority(t *torrent.Torrent, target *torrent.File, curBytePos int64) {
	if target == nil || target.Length() == 0 || t.Info() == nil {
		return
	}
	pieceLen := t.Info().PieceLength
	if pieceLen <= 0 {
		return
	}

	begin := target.BeginPieceIndex()
	end := target.EndPieceIndex()
	numPieces := t.NumPieces()
	if end > numPieces {
		end = numPieces
	}

	fileOffset := target.Offset()
	curPiece := int((fileOffset + curBytePos) / pieceLen)
	if curPiece < begin {
		curPiece = begin
	}
	if curPiece >= end {
		curPiece = end - 1
	}

	// Controlled rolling FIFO buffer window:
	// - Urgent playback buffer: curPiece .. curPiece + 8 (~32 MB) -> PriorityNow
	// - Lookahead buffer: curPiece + 8 .. curPiece + 28 (~80 MB) -> PriorityHigh
	// TOTAL ACTIVE BUFFER AHEAD: ~112 MB (28 pieces max, ~45-60s of 4K)
	urgentEnd := curPiece + 8
	if urgentEnd > end {
		urgentEnd = end
	}
	lookaheadEnd := curPiece + 28
	if lookaheadEnd > end {
		lookaheadEnd = end
	}

	// 1. Deprioritize and CANCEL all trailing pieces behind playback head (< curPiece - 4)
	// (Preserve only container header pieces begin .. begin+1 for seeking)
	pastLimit := curPiece - 4
	if pastLimit > begin+2 {
		for p := begin + 2; p < pastLimit; p++ {
			t.Piece(p).SetPriority(torrent.PiecePriorityNone)
		}
		t.CancelPieces(begin+2, pastLimit)
	}

	// 2. Deprioritize and CANCEL all far-ahead pieces beyond lookaheadEnd (> curPiece + 28)
	// (Preserve trailer pieces end-2 .. end for container footer)
	trailerStart := end - 2
	if trailerStart < lookaheadEnd {
		trailerStart = lookaheadEnd
	}
	if lookaheadEnd < trailerStart {
		for p := lookaheadEnd; p < trailerStart; p++ {
			t.Piece(p).SetPriority(torrent.PiecePriorityNone)
		}
		t.CancelPieces(lookaheadEnd, trailerStart)
	}

	// 3. Queue only the active forward sliding window for download
	for p := curPiece; p < urgentEnd; p++ {
		t.Piece(p).SetPriority(torrent.PiecePriorityNow)
	}
	for p := urgentEnd; p < lookaheadEnd; p++ {
		t.Piece(p).SetPriority(torrent.PiecePriorityHigh)
	}
	t.DownloadPieces(curPiece, lookaheadEnd)

	// 4. Always maintain container header & footer for index parsers
	for p := begin; p < begin+2 && p < end; p++ {
		t.Piece(p).SetPriority(torrent.PiecePriorityHigh)
	}
	if end-begin > 4 {
		for p := end - 2; p < end; p++ {
			t.Piece(p).SetPriority(torrent.PiecePriorityHigh)
		}
	}
}

func (e *Engine) evictPastWatchedPieces(t *torrent.Torrent, target *torrent.File, curBytePos int64) {
	if t == nil || target == nil || t.Info() == nil {
		return
	}
	info := t.Info()
	pieceLen := info.PieceLength
	if pieceLen <= 0 || len(info.Pieces) == 0 {
		return
	}

	begin := target.BeginPieceIndex()
	fileOffset := target.Offset()
	curPiece := int((fileOffset + curBytePos) / pieceLen)

	// Immediate sparse chunk hole punching:
	// As soon as playback advances past container header (begin + 2),
	// delete any piece more than 4 pieces behind (~16 MB / 30-40s ago) immediately.
	safeEvictEnd := curPiece - 4
	if safeEvictEnd > begin+2 {
		for p := begin + 2; p < safeEvictEnd; p++ {
			start := p * 20
			if start+20 > len(info.Pieces) {
				break
			}
			pieceHash := hex.EncodeToString(info.Pieces[start : start+20])
			fullPath := filepath.Join(e.cacheDir, "completed", pieceHash)
			relPath := filepath.ToSlash(filepath.Join("completed", pieceHash))
			incompleteDir := filepath.Join(e.cacheDir, "incompleted", pieceHash)

			// Drop piece in torrent client
			t.Piece(p).SetPriority(torrent.PiecePriorityNone)
			t.CancelPieces(p, p+1)

			// Hole-punch remove from disk & filecache index
			if fi, err := os.Stat(fullPath); err == nil && !fi.IsDir() {
				size := fi.Size()
				_ = e.cache.Remove(relPath)
				_ = os.Remove(fullPath)
				log.Printf("[JewEngine] Hole-punched watched piece %d (%s) freeing %.1f MB", p, pieceHash[:8], float64(size)/(1024*1024))
			} else {
				_ = e.cache.Remove(relPath)
			}
			_ = os.RemoveAll(incompleteDir)
		}
	}

	// Emergency flash protection: if cache still exceeds maxCacheBytes,
	// prune any completed pieces outside the active playing window
	cacheInfo := e.cache.Info()
	capBytes := e.maxCacheBytes.Load()
	if capBytes > 0 && cacheInfo.Filled > capBytes {
		lookaheadEnd := curPiece + 28
		log.Printf("[JewEngine] Flash safety trip: filled %.1f MB > cap %.1f MB. Purging outer pieces...",
			float64(cacheInfo.Filled)/(1024*1024), float64(capBytes)/(1024*1024))
		for p := begin + 2; p < target.EndPieceIndex()-2; p++ {
			if p >= curPiece && p < lookaheadEnd {
				continue // Active window protected
			}
			start := p * 20
			if start+20 > len(info.Pieces) {
				break
			}
			pieceHash := hex.EncodeToString(info.Pieces[start : start+20])
			relPath := filepath.ToSlash(filepath.Join("completed", pieceHash))
			fullPath := filepath.Join(e.cacheDir, "completed", pieceHash)
			_ = e.cache.Remove(relPath)
			_ = os.Remove(fullPath)
			_ = os.RemoveAll(filepath.Join(e.cacheDir, "incompleted", pieceHash))
			t.Piece(p).SetPriority(torrent.PiecePriorityNone)
			t.CancelPieces(p, p+1)
		}
	}
}

type continuousSlidingReader struct {
	reader      torrent.Reader
	t           *torrent.Torrent
	target      *torrent.File
	curPos      atomic.Int64
	lastPrioPos atomic.Int64
}

func newContinuousSlidingReader(r torrent.Reader, t *torrent.Torrent, target *torrent.File, initialPos int64) *continuousSlidingReader {
	csr := &continuousSlidingReader{
		reader: r,
		t:      t,
		target: target,
	}
	csr.curPos.Store(initialPos)
	csr.lastPrioPos.Store(initialPos)
	return csr
}

func (r *continuousSlidingReader) Read(p []byte) (n int, err error) {
	n, err = r.reader.Read(p)
	if n > 0 {
		newPos := r.curPos.Add(int64(n))
		last := r.lastPrioPos.Load()
		if newPos-last >= 4*1024*1024 {
			if r.lastPrioPos.CompareAndSwap(last, newPos) {
				applySlidingWindowPriority(r.t, r.target, newPos)
			}
		}
	}
	return
}

func (r *continuousSlidingReader) Seek(offset int64, whence int) (int64, error) {
	newPos, err := r.reader.Seek(offset, whence)
	if err == nil {
		r.curPos.Store(newPos)
		r.lastPrioPos.Store(newPos)
		applySlidingWindowPriority(r.t, r.target, newPos)
	}
	return newPos, err
}

func (r *continuousSlidingReader) Close() error {
	return r.reader.Close()
}

func (e *Engine) handlePrewarm(w http.ResponseWriter, r *http.Request) {
	hashStr := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("hash")))
	if hashStr == "" {
		http.Error(w, "Missing hash", http.StatusBadRequest)
		return
	}
	var h metainfo.Hash
	if err := h.FromHexString(hashStr); err != nil {
		http.Error(w, "Invalid hex hash", http.StatusBadRequest)
		return
	}

	// Never disturb an active playback session
	if e.activeStreams.Load() > 0 {
		writeJSON(w, http.StatusOK, map[string]string{"status": "skipped_active_stream"})
		return
	}

	e.mu.Lock()
	if e.prewarmCancel != nil {
		e.prewarmCancel()
		e.prewarmCancel = nil
	}
	for prevHash, prevT := range e.activeTorrents {
		if prevHash != hashStr {
			prevT.Drop()
			delete(e.activeTorrents, prevHash)
			delete(e.speedTrackers, prevHash)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	e.prewarmCancel = cancel
	e.prewarmHash = hashStr

	t, isNew := e.client.AddTorrentInfoHash(h)
	if isNew {
		e.addTrackers(t, r)
	}
	e.activeTorrents[hashStr] = t
	idxStr := r.URL.Query().Get("idx")
	e.mu.Unlock()

	go func() {
		defer cancel()
		go fetchFastMetainfo(ctx, h, t)
		select {
		case <-t.GotInfo():
			target := selectTargetFile(t.Files(), idxStr)
			if target != nil {
				target.SetPriority(torrent.PiecePriorityNone)
				t.AllowDataDownload()
				t.DisallowDataUpload()
				warmContainerBoundaries(t, target)
				applySlidingWindowPriority(t, target, 0)
			}
		case <-ctx.Done():
		}
	}()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status": "warming",
		"hash":   hashStr,
		"ready":  t.Info() != nil,
	})
}

func (e *Engine) handleStats(w http.ResponseWriter, r *http.Request) {
	info := e.cache.Info()
	hashStr := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("hash")))
	if hashStr == "" {
		e.mu.Lock()
		activeCount := len(e.activeTorrents)
		e.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"cache_bytes":     info.Filled,
			"cache_cap_bytes": e.maxCacheBytes.Load(),
			"cache_items":     info.NumItems,
			"active_torrents": activeCount,
			"active_streams":  e.activeStreams.Load(),
		})
		return
	}

	var h metainfo.Hash
	if err := h.FromHexString(hashStr); err != nil {
		http.Error(w, "Invalid hex hash: "+err.Error(), http.StatusBadRequest)
		return
	}

	t, ok := e.client.Torrent(h)
	if !ok {
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"hash":            hashStr,
			"status":          "initializing",
			"cache_bytes":     info.Filled,
			"cache_cap_bytes": e.maxCacheBytes.Load(),
		})
		return
	}

	stats := t.Stats()
	var progress float64
	length := t.Length()
	completed := t.BytesCompleted()
	if length > 0 {
		progress = float64(completed) / float64(length)
	}

	numPieces := 0
	piecesComplete := 0
	if t.Info() != nil {
		numPieces = t.NumPieces()
		piecesComplete = stats.PiecesComplete
	}

	e.mu.Lock()
	st, exists := e.speedTrackers[hashStr]
	now := time.Now()
	var speedBps float64
	if !exists {
		e.speedTrackers[hashStr] = &SpeedTracker{
			lastBytes: completed,
			lastTime:  now,
		}
	} else {
		dt := now.Sub(st.lastTime).Seconds()
		if dt >= 0.4 {
			delta := completed - st.lastBytes
			if delta > 0 {
				speedBps = float64(delta) / dt
			} else if stats.ActivePeers > 0 {
				// Smooth exponential decay instead of flickering abruptly to 0
				speedBps = st.speed * 0.5
			}
			st.lastBytes = completed
			st.lastTime = now
			st.speed = speedBps
		} else {
			speedBps = st.speed
		}
	}
	e.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"hash":            hashStr,
		"name":            t.Name(),
		"info_ready":      t.Info() != nil,
		"active_peers":    stats.ActivePeers,
		"total_peers":     stats.TotalPeers,
		"pieces_complete": piecesComplete,
		"total_pieces":    numPieces,
		"bytes_completed": completed,
		"length":          length,
		"progress":        progress,
		"download_speed":  speedBps,
		"cache_bytes":     info.Filled,
		"cache_cap_bytes": e.maxCacheBytes.Load(),
	})
}

type byteRange struct {
	hasRange bool
	start    int64
	end      int64
	span     int64
}

func parseRangeHeader(header string, totalLen int64) byteRange {
	if header == "" || !strings.HasPrefix(strings.ToLower(header), "bytes=") || totalLen <= 0 {
		return byteRange{hasRange: false, start: 0, end: totalLen - 1, span: totalLen}
	}
	spec := strings.TrimSpace(header[len("bytes="):])
	if comma := strings.IndexByte(spec, ','); comma != -1 {
		spec = spec[:comma]
	}
	dash := strings.IndexByte(spec, '-')
	if dash == -1 {
		return byteRange{hasRange: false, start: 0, end: totalLen - 1, span: totalLen}
	}
	left := strings.TrimSpace(spec[:dash])
	right := strings.TrimSpace(spec[dash+1:])

	var start, end int64
	if left == "" {
		// Suffix range: bytes=-N
		suffix, err := strconv.ParseInt(right, 10, 64)
		if err != nil || suffix <= 0 {
			return byteRange{hasRange: true, start: 0, end: totalLen - 1, span: totalLen}
		}
		start = totalLen - suffix
		if start < 0 {
			start = 0
		}
		end = totalLen - 1
	} else {
		var err error
		start, err = strconv.ParseInt(left, 10, 64)
		if err != nil || start < 0 {
			start = 0
		}
		if right == "" {
			end = totalLen - 1
		} else {
			end, err = strconv.ParseInt(right, 10, 64)
			if err != nil || end >= totalLen {
				end = totalLen - 1
			}
		}
	}
	if end < start {
		end = totalLen - 1
	}
	return byteRange{
		hasRange: true,
		start:    start,
		end:      end,
		span:     (end - start) + 1,
	}
}

func (e *Engine) handleStream(w http.ResponseWriter, r *http.Request) {
	hashStr := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("hash")))
	if hashStr == "" {
		http.Error(w, "Missing hash parameter", http.StatusBadRequest)
		return
	}

	var h metainfo.Hash
	if err := h.FromHexString(hashStr); err != nil {
		http.Error(w, "Invalid hex hash: "+err.Error(), http.StatusBadRequest)
		return
	}

	e.activeStreams.Add(1)
	defer e.activeStreams.Add(-1)

	e.mu.Lock()
	if e.prewarmCancel != nil {
		e.prewarmCancel()
		e.prewarmCancel = nil
	}
	for prevHash, prevT := range e.activeTorrents {
		if prevHash != hashStr {
			log.Printf("[JewEngine] Switching torrent: dropping %s", prevHash)
			prevT.Drop()
			delete(e.activeTorrents, prevHash)
			delete(e.speedTrackers, prevHash)
		}
	}
	t, isNew := e.client.AddTorrentInfoHash(h)
	if isNew {
		log.Printf("[JewEngine] Registered stream infohash: %s", hashStr)
		e.addTrackers(t, r)
	}
	e.activeTorrents[hashStr] = t
	e.mu.Unlock()

	ctx := r.Context()
	fastMetaCtx, fastMetaCancel := context.WithTimeout(ctx, 6*time.Second)
	go func() {
		defer fastMetaCancel()
		fetchFastMetainfo(fastMetaCtx, h, t)
	}()

	select {
	case <-t.GotInfo():
		log.Printf("[JewEngine] Metadata ready for %s (files=%d)", hashStr, len(t.Files()))
	case <-time.After(25 * time.Second):
		log.Printf("[JewEngine] Timed out resolving metadata for %s", hashStr)
		http.Error(w, "Timeout fetching torrent metadata", http.StatusGatewayTimeout)
		return
	case <-ctx.Done():
		return
	}

	files := t.Files()
	targetFile := selectTargetFile(files, r.URL.Query().Get("idx"))
	if targetFile == nil || targetFile.Length() == 0 {
		http.Error(w, "Video file not found", http.StatusNotFound)
		return
	}

	fileLen := targetFile.Length()
	br := parseRangeHeader(r.Header.Get("Range"), fileLen)
	ext := strings.ToLower(filepath.Ext(targetFile.DisplayPath()))

	// Distinguish short container probes / EOF seeks from main sequential video stream
	isFiniteShortProbe := br.hasRange && br.span <= 2*1024*1024
	isTailSeek := fileLen > 8*1024*1024 && br.start >= fileLen-4*1024*1024
	isProbe := isFiniteShortProbe || isTailSeek

	log.Printf("[STREAM-DEBUG] Range: %q, fileLen: %d, isProbe: %v, start: %d, span: %d", r.Header.Get("Range"), fileLen, isProbe, br.start, br.span)

	// Default all pieces to None; sliding window will selectively download only active runway
	targetFile.SetPriority(torrent.PiecePriorityNone)
	t.AllowDataDownload()
	t.DisallowDataUpload()

	// Immediately pull container header and trailer atoms in parallel
	warmContainerBoundaries(t, targetFile)

	rawReader := targetFile.NewReader()
	defer rawReader.Close()

	// CRITICAL FOR ZERO-STALL STREAMING: stream chunks immediately without waiting for full piece hash verification
	rawReader.SetResponsive()

	if isProbe {
		rawReader.SetReadahead(4 * 1024 * 1024)
	} else {
		rawReader.SetReadahead(48 * 1024 * 1024) // 48 MB readahead (~12 pieces ahead) for snappy 4K flow
	}

	csr := newContinuousSlidingReader(rawReader, t, targetFile, br.start)
	applySlidingWindowPriority(t, targetFile, br.start)

	if !isProbe {
		stopTicker := make(chan struct{})
		defer close(stopTicker)
		go func() {
			ticker := time.NewTicker(1200 * time.Millisecond)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					pos := csr.curPos.Load()
					applySlidingWindowPriority(t, targetFile, pos)
					e.evictPastWatchedPieces(t, targetFile, pos)
				case <-stopTicker:
					return
				case <-ctx.Done():
					return
				}
			}
		}()
	}

	mimeType := mime.TypeByExtension(ext)
	if mimeType == "" {
		switch ext {
		case ".mkv":
			mimeType = "video/x-matroska"
		case ".mp4", ".m4v":
			mimeType = "video/mp4"
		case ".webm":
			mimeType = "video/webm"
		case ".avi":
			mimeType = "video/x-msvideo"
		default:
			mimeType = "application/octet-stream"
		}
	}

	w.Header().Set("Content-Type", mimeType)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", filepath.Base(targetFile.DisplayPath())))

	http.ServeContent(w, r, targetFile.DisplayPath(), time.Time{}, csr)
}
