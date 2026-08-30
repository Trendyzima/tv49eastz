package protocol

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
)

const (
	Version byte = 1
	Register byte = 1
	Request byte = 2
	Response byte = 3
	Error byte = 4
	MaxFrame = 32 << 20
)

type Header struct {
	Version byte `json:"version"`
	Type byte `json:"type"`
	ID uint64 `json:"id"`
	Status int `json:"status,omitempty"`
	Method string `json:"method,omitempty"`
	Path string `json:"path,omitempty"`
	ContentType string `json:"content_type,omitempty"`
	ContentLength int64 `json:"content_length,omitempty"`
	DeviceID string `json:"device_id,omitempty"`
	Error string `json:"error,omitempty"`
}

type Frame struct { Header Header; Body []byte }

func Write(conn net.Conn, f Frame) error {
	h, err := json.Marshal(f.Header); if err != nil { return err }
	if len(h) > 64<<10 || len(f.Body) > MaxFrame { return errors.New("frame too large") }
	var b [8]byte
	binary.BigEndian.PutUint32(b[:4], uint32(len(h)))
	binary.BigEndian.PutUint32(b[4:], uint32(len(f.Body)))
	if _, err = conn.Write(b[:]); err != nil { return err }
	if _, err = conn.Write(h); err != nil { return err }
	_, err = conn.Write(f.Body); return err
}

func Read(r *bufio.Reader) (Frame, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil { return Frame{}, err }
	hl, bl := binary.BigEndian.Uint32(b[:4]), binary.BigEndian.Uint32(b[4:])
	if hl == 0 || hl > 64<<10 || bl > MaxFrame { return Frame{}, fmt.Errorf("invalid frame lengths %d/%d", hl, bl) }
	h := make([]byte, hl); if _, err := io.ReadFull(r, h); err != nil { return Frame{}, err }
	var header Header; if err := json.Unmarshal(h, &header); err != nil { return Frame{}, err }
	if header.Version != Version { return Frame{}, fmt.Errorf("unsupported protocol version %d", header.Version) }
	body := make([]byte, bl); if _, err := io.ReadFull(r, body); err != nil { return Frame{}, err }
	return Frame{Header: header, Body: body}, nil
}
