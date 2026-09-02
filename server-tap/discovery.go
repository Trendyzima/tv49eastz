package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultFadCamPort       = 8080
	defaultDiscoveryTimeout = 800 * time.Millisecond
	defaultDiscoveryWorkers = 32
	defaultDiscoveryMaxHost = 512
)

// discoverFadCamUpstream locates a FadCam HTTP server without requiring an IP
// in the deployment environment. It checks loopback first, then local
// interface addresses, then directly-connected IPv4 subnets. A candidate is
// accepted only when /live.m3u8 looks like a real HLS playlist, which keeps
// unrelated services on port 8080 from being selected accidentally.
func discoverFadCamUpstream(ctx context.Context) (*url.URL, error) {
	port := envPositiveInt("TAP_DISCOVERY_PORT", defaultFadCamPort)
	timeout := envDuration("TAP_DISCOVERY_TIMEOUT", defaultDiscoveryTimeout)
	maxHosts := envPositiveInt("TAP_DISCOVERY_MAX_HOSTS", defaultDiscoveryMaxHost)
	workers := envPositiveInt("TAP_DISCOVERY_WORKERS", defaultDiscoveryWorkers)
	if workers > 64 {
		workers = 64
	}

	candidates, err := discoveryCandidates(port, maxHosts)
	if err != nil {
		return nil, err
	}

	probeClient := &http.Client{
		Timeout: timeout,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return errors.New("discovery redirects are disabled")
		},
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	jobs := make(chan string)
	found := make(chan *url.URL, 1)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for host := range jobs {
				if ctx.Err() != nil {
					return
				}
				candidate := &url.URL{Scheme: "http", Host: host}
				if probeFadCam(ctx, probeClient, candidate) {
					select {
					case found <- candidate:
						cancel()
					case <-ctx.Done():
					}
					return
				}
			}
		}()
	}

	go func() {
		defer close(jobs)
		for _, candidate := range candidates {
			select {
			case jobs <- candidate:
			case <-ctx.Done():
				return
			}
		}
	}()

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case u := <-found:
		return u, nil
	case <-done:
		return nil, fmt.Errorf("no FadCam HLS server discovered on port %d", port)
	case <-ctx.Done():
		select {
		case u := <-found:
			return u, nil
		default:
			return nil, ctx.Err()
		}
	}
}

func probeFadCam(ctx context.Context, client *http.Client, base *url.URL) bool {
	endpoint := *base
	endpoint.Path = "/live.m3u8"
	endpoint.RawQuery = ""

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return false
	}
	req.Header.Set("Accept", "application/vnd.apple.mpegurl,text/plain;q=0.8")
	req.Header.Set("User-Agent", "tv49eastz-fadcam-discovery/1")

	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 128<<10))
	if err != nil {
		return false
	}
	playlist := string(body)
	if !strings.HasPrefix(strings.TrimSpace(playlist), "#EXTM3U") {
		return false
	}
	// Current FadCam live output exposes the fragmented-MP4 init segment.
	// Accept segment playlists too, but require an actual media reference.
	return strings.Contains(playlist, "/init.mp4") ||
		strings.Contains(playlist, "#EXT-X-MAP:") ||
		strings.Contains(playlist, ".m4s") ||
		strings.Contains(playlist, ".mp4")
}

func discoveryCandidates(port, maxHosts int) ([]string, error) {
	seen := make(map[string]struct{})
	candidates := make([]string, 0, maxHosts+8)
	add := func(ip net.IP) {
		if ip == nil {
			return
		}
		v4 := ip.To4()
		if v4 == nil {
			return
		}
		host := net.JoinHostPort(v4.String(), strconv.Itoa(port))
		if _, ok := seen[host]; ok {
			return
		}
		seen[host] = struct{}{}
		candidates = append(candidates, host)
	}

	// Loopback is intentionally first: normal FadCam/server-tap deployments
	// run both components on the same production device.
	add(net.ParseIP("127.0.0.1"))
	interfaces, err := net.Interfaces()
	if err != nil {
		return candidates, err
	}

	type network struct {
		ip   net.IP
		mask net.IPMask
	}
	var networks []network
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			var mask net.IPMask
			switch value := addr.(type) {
			case *net.IPNet:
				ip, mask = value.IP.To4(), value.Mask
			case *net.IPAddr:
				ip = value.IP.To4()
			}
			if ip == nil || mask == nil || len(mask) != net.IPv4len {
				continue
			}
			add(ip)
			networks = append(networks, network{ip: ip, mask: mask})
		}
	}

	// Scan directly-connected IPv4 networks. The normal server-room Wi-Fi
	// layout is /24. Larger networks are capped to avoid a runaway startup
	// scan; TAP_DISCOVERY_MAX_HOSTS can be raised when a larger LAN is needed.
	for _, n := range networks {
		networkIP := n.ip.Mask(n.mask).To4()
		hostBits := 32
		for _, b := range n.mask {
			for bit := byte(0x80); bit != 0; bit >>= 1 {
				if b&bit == 0 {
					goto networkBitsDone
				}
				hostBits--
			}
		}
	networkBitsDone:
		if hostBits < 0 || hostBits > 24 {
			continue
		}
		hostCount := 1 << hostBits
		if hostCount > maxHosts {
			hostCount = maxHosts
		}
		base := binaryIPv4(networkIP)
		for offset := 1; offset < hostCount-1 && len(candidates) < maxHosts; offset++ {
			add(uint32IPv4(base + uint32(offset)))
		}
	}

	return candidates, nil
}

func binaryIPv4(ip net.IP) uint32 {
	v4 := ip.To4()
	if v4 == nil {
		return 0
	}
	return uint32(v4[0])<<24 | uint32(v4[1])<<16 | uint32(v4[2])<<8 | uint32(v4[3])
}

func uint32IPv4(v uint32) net.IP {
	return net.IPv4(byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

func envPositiveInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	n, err := strconv.Atoi(value)
	if err != nil || n <= 0 {
		return fallback
	}
	return n
}

func envDuration(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	d, err := time.ParseDuration(value)
	if err != nil || d <= 0 {
		return fallback
	}
	return d
}
