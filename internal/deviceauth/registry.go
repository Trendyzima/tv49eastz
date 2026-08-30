package deviceauth

import (
    "crypto/sha256"
    "crypto/x509"
    "encoding/hex"
    "encoding/json"
    "errors"
    "os"
    "path/filepath"
    "strings"
    "sync"
    "time"
)

type Record struct {
    DeviceID string `json:"device_id"`
    PrincipalID string `json:"principal_id"`
    Fingerprint string `json:"fingerprint"`
    Channels map[string]bool `json:"channels"`
    Enabled bool `json:"enabled"`
    RevokedAt *time.Time `json:"revoked_at,omitempty"`
}

type Registry struct { mu sync.RWMutex; path string; devices map[string]Record; subscribers map[chan Event]struct{} }
type Event struct { DeviceID string; Revoked bool; Enabled bool }

func Open(path string) (*Registry,error) {
    if strings.TrimSpace(path)=="" { return nil,errors.New("registry path required") }
    r:=&Registry{path:path,devices:map[string]Record{},subscribers:map[chan Event]struct{}{}}
    b,e:=os.ReadFile(path); if os.IsNotExist(e){return r,nil}; if e!=nil{return nil,e}
    if len(b)>0 && json.Unmarshal(b,&r.devices)!=nil{return nil,errors.New("invalid device registry")}; return r,nil
}
func Fingerprint(cert *x509.Certificate) string { s:=sha256.Sum256(cert.Raw); return hex.EncodeToString(s[:]) }
func (r *Registry) persistLocked() error { if dir:=filepath.Dir(r.path); dir!="." { if e:=os.MkdirAll(dir,0700);e!=nil{return e} }; b,e:=json.MarshalIndent(r.devices,"","  ");if e!=nil{return e};tmp:=r.path+".tmp";if e=os.WriteFile(tmp,b,0600);e!=nil{return e};return os.Rename(tmp,r.path) }
func (r *Registry) Upsert(d Record) error { if d.DeviceID==""||d.PrincipalID==""||d.Fingerprint==""{return errors.New("device identity fields are required")};r.mu.Lock();defer r.mu.Unlock();if old,ok:=r.devices[d.DeviceID];ok&&old.Fingerprint!=d.Fingerprint{return errors.New("device identity already bound to another certificate")};for id,old:=range r.devices{if id!=d.DeviceID&&strings.EqualFold(old.Fingerprint,d.Fingerprint){return errors.New("certificate already bound to another device")}};if d.Channels==nil{d.Channels=map[string]bool{}};r.devices[d.DeviceID]=d;return r.persistLocked() }
func (r *Registry) ResolveDevice(id string)(Record,bool){r.mu.RLock();d,ok:=r.devices[strings.TrimSpace(id)];r.mu.RUnlock();return d,ok&&d.Enabled&&d.RevokedAt==nil}
func (r *Registry) ResolveCertificate(cert *x509.Certificate)(Record,bool){if cert==nil{return Record{},false};fp:=Fingerprint(cert);r.mu.RLock();defer r.mu.RUnlock();for _,d:=range r.devices{if strings.EqualFold(d.Fingerprint,fp)&&d.Enabled&&d.RevokedAt==nil{return d,true}};return Record{},false}
func (r *Registry) Authorize(id,principal,channel,stream string)(Record,bool){d,ok:=r.ResolveDevice(id);if !ok||d.PrincipalID!=principal||channel==""||!d.Channels[channel]{return Record{},false};if stream==""{return Record{},false};return d,true}
func (r *Registry) Revoke(id string) error {r.mu.Lock();defer r.mu.Unlock();d,ok:=r.devices[id];if !ok{return errors.New("device not enrolled")};now:=time.Now().UTC();d.RevokedAt=&now;d.Enabled=false;r.devices[id]=d;if e:=r.persistLocked();e!=nil{return e};r.publishLocked(Event{DeviceID:id,Revoked:true,Enabled:false});return nil}
func (r *Registry) SetEnabled(id string,enabled bool) error {r.mu.Lock();defer r.mu.Unlock();d,ok:=r.devices[id];if !ok{return errors.New("device not enrolled")};d.Enabled=enabled;r.devices[id]=d;if e:=r.persistLocked();e!=nil{return e};r.publishLocked(Event{DeviceID:id,Revoked:d.RevokedAt!=nil,Enabled:enabled});return nil}
func (r *Registry) Subscribe(buffer int) (<-chan Event,func()){if buffer<1{buffer=1};ch:=make(chan Event,buffer);r.mu.Lock();r.subscribers[ch]=struct{}{};r.mu.Unlock();return ch,func(){r.mu.Lock();if _,ok:=r.subscribers[ch];ok{delete(r.subscribers,ch);close(ch)};r.mu.Unlock()}}
func (r *Registry) publishLocked(ev Event){for ch:=range r.subscribers{select{case ch<-ev:default:}}}
