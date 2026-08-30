package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

type DeviceRecord struct { DeviceID string; PrincipalID string; Fingerprint string; Channels map[string]bool; Enabled bool }
type DeviceRegistry struct { mu sync.RWMutex; byID map[string]DeviceRecord; byFP map[string]string; remoteURL string; client *http.Client }

func NewDeviceRegistry() *DeviceRegistry { return &DeviceRegistry{byID:make(map[string]DeviceRecord),byFP:make(map[string]string),remoteURL:strings.TrimRight(os.Getenv("DEVICE_REGISTRY_URL"),"/"),client:&http.Client{Timeout:3*time.Second,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}} }
func (r *DeviceRegistry) Register(d DeviceRecord) error { if d.DeviceID==""||d.PrincipalID==""||d.Fingerprint==""{return errors.New("device identity fields are required")};r.mu.Lock();defer r.mu.Unlock();if old,ok:=r.byID[d.DeviceID];ok&&old.Fingerprint!=d.Fingerprint{return errors.New("device identity already bound to another certificate")};if old,ok:=r.byFP[d.Fingerprint];ok&&old!=d.DeviceID{return errors.New("certificate already bound to another device")};r.byID[d.DeviceID]=d;r.byFP[d.Fingerprint]=d.DeviceID;return nil }
func (r *DeviceRegistry) Lookup(deviceID string)(DeviceRecord,bool){deviceID=normalizeDeviceID(deviceID);if r.remoteURL!=""{u,err:=url.Parse(r.remoteURL+"/registry/authorize");if err!=nil{return DeviceRecord{},false};q:=u.Query();q.Set("device_id",deviceID);u.RawQuery=q.Encode();resp,err:=r.client.Get(u.String());if err!=nil{return DeviceRecord{},false};defer resp.Body.Close();if resp.StatusCode!=http.StatusOK{return DeviceRecord{},false};var v struct{DeviceID,PrincipalID string;Enabled,Revoked bool};if json.NewDecoder(resp.Body).Decode(&v)!=nil||v.Revoked||!v.Enabled{return DeviceRecord{},false};return DeviceRecord{DeviceID:v.DeviceID,PrincipalID:v.PrincipalID,Enabled:v.Enabled,Channels:map[string]bool{v.Channel:true}},true};r.mu.RLock();d,ok:=r.byID[deviceID];r.mu.RUnlock();return d,ok&&d.Enabled }
func (r *DeviceRegistry) Verify(cert *x509.Certificate)(DeviceRecord,bool){if cert==nil{return DeviceRecord{},false};if err:=validateCertificate(cert,time.Now());err!=nil{return DeviceRecord{},false};fp:=certificateFingerprint(cert);r.mu.RLock();deviceID,ok:=r.byFP[fp];d,exists:=r.byID[deviceID];r.mu.RUnlock();return d,ok&&exists&&d.Enabled}
func certificateFingerprint(cert *x509.Certificate)string{s:=sha256.Sum256(cert.Raw);return hex.EncodeToString(s[:])}
func validateCertificate(cert *x509.Certificate,now time.Time)error{if cert==nil{return errors.New("missing certificate")};if now.Before(cert.NotBefore)||!now.Before(cert.NotAfter){return errors.New("certificate expired or not yet valid")};return nil}
func channelAllowed(d DeviceRecord,channelID string)bool{return d.Enabled&&channelID!=""&&d.Channels[channelID]}
func normalizeDeviceID(s string)string{return strings.TrimSpace(s)}
