package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type CreatorChannel struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Owner     string    `json:"owner"`
	Country   string    `json:"country,omitempty"`
	Language  string    `json:"language,omitempty"`
	Logo      string    `json:"logo,omitempty"`
	Stream    string    `json:"stream"`
	Source    string    `json:"source"`
	Published time.Time `json:"published"`
}

type creatorRegistry struct {
	Channels []CreatorChannel `json:"channels"`
}

type creatorStore struct {
	mu   sync.RWMutex
	path string
	data creatorRegistry
}

func newCreatorStore(path string) (*creatorStore, error) {
	s := &creatorStore{path: path}
	if path == "" {
		return s, nil
	}
	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *creatorStore) load() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		s.data = creatorRegistry{}
		return nil
	}
	if err != nil {
		return err
	}
	var data creatorRegistry
	if err := json.Unmarshal(b, &data); err != nil {
		return err
	}
	for _, ch := range data.Channels {
		if ch.ID == "" || ch.Name == "" || ch.Stream == "" || !isHTTPS(ch.Stream) {
			return errors.New("creator registry contains an invalid channel")
		}
	}
	s.data = data
	return nil
}

func (s *creatorStore) list() []CreatorChannel {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]CreatorChannel, len(s.data.Channels))
	copy(out, s.data.Channels)
	return out
}

func (s *creatorStore) upsert(ch CreatorChannel) error {
	if ch.ID == "" || ch.Name == "" || !isHTTPS(ch.Stream) {
		return errors.New("creator channel requires an HTTPS stream")
	}
	if ch.Source == "" {
		ch.Source = "fadcam"
	}
	if ch.Published.IsZero() {
		ch.Published = time.Now().UTC()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.data.Channels {
		if s.data.Channels[i].ID == ch.ID {
			s.data.Channels[i] = ch
			return s.persistLocked()
		}
	}
	s.data.Channels = append(s.data.Channels, ch)
	return s.persistLocked()
}

func (s *creatorStore) remove(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.data.Channels[:0]
	for _, ch := range s.data.Channels {
		if ch.ID != id {
			out = append(out, ch)
		}
	}
	s.data.Channels = out
	return s.persistLocked()
}

func (s *creatorStore) persistLocked() error {
	if s.path == "" {
		return nil
	}
	b, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err = f.Write(b); err == nil {
		err = f.Sync()
	}
	if closeErr := f.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	// Sync the parent directory so the rename itself survives a crash on filesystems
	// that support directory fsync. Failure is surfaced rather than reporting false durability.
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	err = d.Sync()
	closeErr := d.Close()
	if err == nil {
		err = closeErr
	}
	return err
}

func isHTTPS(raw string) bool {
	return strings.HasPrefix(strings.ToLower(strings.TrimSpace(raw)), "https://")
}
