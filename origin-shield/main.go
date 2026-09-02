package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct {
	Listen              string
	CatalogURL          string
	CatalogKey          string
	TunnelURL           string
	CacheBytes          int64
	MaxObjectBytes      int64
	SegmentTTL          time.Duration
	OriginTimeout       time.Duration
	MaxOriginConcurrent int
	TargetTTL           time.Duration
}

type originTarget struct { ChannelID string `json:"channel_id"`; DeviceID string `json:"device_id"`; StreamPath string `json:"stream_path"`; Source string `json:"source"` }

type cacheEntry struct { body []byte; contentType string; expires time.Time }
type cache struct { mu sync.Mutex; maxBytes int64; bytes int64; items map[string]cacheEntry; order []string }
func newCache(max int64) *cache { return &cache{maxBytes:max,items:make(map[string]cacheEntry)} }
func (c *cache) get(key string, now time.Time) (cacheEntry,bool) { c.mu.Lock(); defer c.mu.Unlock(); e,ok:=c.items[key]; if !ok{return cacheEntry{},false}; if !now.Before(e.expires){c.removeLocked(key);return cacheEntry{},false}; return e,true }
func (c *cache) put(key string,e cacheEntry){if int64(len(e.body))>c.maxBytes{return};c.mu.Lock();defer c.mu.Unlock();if old,ok:=c.items[key];ok{c.bytes-=int64(len(old.body));c.removeOrderLocked(key)};c.items[key]=e;c.order=append(c.order,key);c.bytes+=int64(len(e.body));for c.bytes>c.maxBytes&&len(c.order)>0{victim:=c.order[0];c.order=c.order[1:];if old,ok:=c.items[victim];ok{c.bytes-=int64(len(old.body));delete(c.items,victim)}}}
func (c *cache) removeLocked(key string){if e,ok:=c.items[key];ok{c.bytes-=int64(len(e.body));delete(c.items,key);c.removeOrderLocked(key)}}
func (c *cache) removeOrderLocked(key string){for i,k:=range c.order{if k==key{c.order=append(c.order[:i],c.order[i+1:]...);return}}}
func (c *cache) stats()(int64,int){c.mu.Lock();defer c.mu.Unlock();return c.bytes,len(c.items)}

type call struct { done chan struct{}; entry cacheEntry; err error }
type shield struct { cfg Config; client *http.Client; cache *cache; mu sync.Mutex; calls map[string]*call; targets map[string]originTarget; targetExp map[string]time.Time; originSem chan struct{}; requests uint64; hits uint64; misses uint64; originFetches uint64; originErrors uint64; targetRefreshes uint64 }

func main(){cfg,err:=loadConfig();if err!=nil{log.Fatal(err)};s:=newShield(cfg);mux:=http.NewServeMux();mux.HandleFunc("/health",s.health);mux.HandleFunc("/metrics",s.metrics);mux.HandleFunc("/channel/",s.channel);srv:=&http.Server{Addr:cfg.Listen,Handler:securityHeaders(mux),ReadHeaderTimeout:5*time.Second,IdleTimeout:30*time.Second,MaxHeaderBytes:16<<10};log.Printf("TV 49 East origin shield listening on %s",cfg.Listen);log.Fatal(srv.ListenAndServe())}

func loadConfig()(Config,error){c:=Config{Listen:getenv("SHIELD_LISTEN",":8795"),CatalogURL:strings.TrimRight(strings.TrimSpace(os.Getenv("SHIELD_CATALOG_URL")),"/"),CatalogKey:strings.TrimSpace(os.Getenv("SHIELD_CATALOG_KEY")),TunnelURL:strings.TrimRight(strings.TrimSpace(os.Getenv("SHIELD_TUNNEL_URL")),"/"),CacheBytes:positiveInt64(getenv("SHIELD_CACHE_BYTES","536870912"),512<<20),MaxObjectBytes:positiveInt64(getenv("SHIELD_MAX_OBJECT_BYTES","33554432"),32<<20),SegmentTTL:positiveDuration(getenv("SHIELD_SEGMENT_TTL","30s"),30*time.Second),OriginTimeout:positiveDuration(getenv("SHIELD_ORIGIN_TIMEOUT","10s"),10*time.Second),MaxOriginConcurrent:int(positiveInt64(getenv("SHIELD_MAX_ORIGIN_CONCURRENCY","256"),256)),TargetTTL:positiveDuration(getenv("SHIELD_TARGET_TTL","30s"),30*time.Second)};if c.CatalogURL==""{return c,errors.New("SHIELD_CATALOG_URL is required")};if c.CatalogKey==""{return c,errors.New("SHIELD_CATALOG_KEY is required")};if c.TunnelURL==""{return c,errors.New("SHIELD_TUNNEL_URL is required")};u,err:=url.Parse(c.CatalogURL);if err!=nil||u.Scheme!="https"||u.Host==""{return c,errors.New("SHIELD_CATALOG_URL must be HTTPS")};tu,err:=url.Parse(c.TunnelURL);if err!=nil||tu.Scheme!="http"||tu.Host==""{return c,errors.New("SHIELD_TUNNEL_URL must be HTTP(S) reachable from the shield")};return c,nil}
func positiveInt64(v string,d int64)int64{n,e:=strconv.ParseInt(strings.TrimSpace(v),10,64);if e!=nil||n<=0{return d};return n}
func positiveDuration(v string,d time.Duration)time.Duration{x,e:=time.ParseDuration(strings.TrimSpace(v));if e!=nil||x<=0{return d};return x}
func getenv(k,d string)string{if v:=os.Getenv(k);v!=""{return v};return d}
func newShield(c Config)*shield{return &shield{cfg:c,client:&http.Client{Timeout:c.OriginTimeout,CheckRedirect:func(*http.Request,[]*http.Request)error{return errors.New("redirects disabled")},Transport:&http.Transport{Proxy:nil,MaxIdleConns:512,MaxIdleConnsPerHost:256,IdleConnTimeout:30*time.Second,DialContext:(&net.Dialer{Timeout:5*time.Second,KeepAlive:30*time.Second}).DialContext}},cache:newCache(c.CacheBytes),calls:make(map[string]*call),targets:make(map[string]originTarget),targetExp:make(map[string]time.Time),originSem:make(chan struct{},c.MaxOriginConcurrent)}}

func (s *shield) channel(w http.ResponseWriter,r *http.Request){atomic.AddUint64(&s.requests,1);if r.Method!=http.MethodGet&&r.Method!=http.MethodHead{http.Error(w,"method not allowed",405);return};channelID,asset,ok:=parseChannelPath(r.URL.Path);if !ok{http.Error(w,"invalid channel path",400);return};if !validAsset(asset){http.Error(w,"unsupported asset",404);return};if asset=="/live.m3u8"{s.servePlaylist(w,r,channelID);return};s.serveAsset(w,r,channelID,asset)}
func parseChannelPath(p string)(string,string,bool){const prefix="/channel/";if !strings.HasPrefix(p,prefix){return "","",false};rest:=strings.TrimPrefix(p,prefix);i:=strings.IndexByte(rest,'/');if i<=0||i==len(rest)-1{return "","",false};id:=rest[:i];asset:=rest[i:];if strings.ContainsAny(id,"/\\\x00\r\n")||strings.Contains(id,"..")||len(id)>256{return "","",false};return id,asset,true}
func validAsset(p string)bool{if p==""||!strings.HasPrefix(p,"/")||strings.ContainsAny(p,"\x00\r\n")||strings.Contains(p,"..")||strings.HasPrefix(p,"//"){return false};if p=="/live.m3u8"{return true};low:=strings.ToLower(p);return strings.HasSuffix(low,".m4s")||strings.HasSuffix(low,".mp4")||strings.HasSuffix(low,".ts")||strings.HasSuffix(low,".m3u8")}

func (s *shield) resolve(ctx context.Context,id string)(originTarget,error){s.mu.Lock();if t,ok:=s.targets[id];ok&&time.Now().Before(s.targetExp[id]){s.mu.Unlock();return t,nil};s.mu.Unlock();req,err:=http.NewRequestWithContext(ctx,http.MethodGet,s.cfg.CatalogURL+"/v1/origin/channels?id="+url.QueryEscape(id),nil);if err!=nil{return originTarget{},err};req.Header.Set("X-Origin-Key",s.cfg.CatalogKey);resp,err:=s.client.Do(req);if err!=nil{return originTarget{},err};defer resp.Body.Close();if resp.StatusCode!=200{return originTarget{},fmt.Errorf("catalog returned HTTP %d",resp.StatusCode)};var t originTarget;if err:=json.NewDecoder(io.LimitReader(resp.Body,64<<10)).Decode(&t);err!=nil{return originTarget{},err};if t.ChannelID!=id||t.DeviceID==""||strings.Contains(t.DeviceID,"/")||strings.Contains(t.DeviceID,"..")||t.Source!="fadcam"{return originTarget{},errors.New("catalog returned invalid FadCam origin")};if t.StreamPath==""{t.StreamPath="/live.m3u8"};if t.StreamPath!="/live.m3u8"{return originTarget{},errors.New("catalog returned invalid FadCam stream path")};s.mu.Lock();s.targets[id]=t;s.targetExp[id]=time.Now().Add(s.cfg.TargetTTL);s.mu.Unlock();atomic.AddUint64(&s.targetRefreshes,1);return t,nil}
func (s *shield) invalidate(id string){s.mu.Lock();delete(s.targets,id);delete(s.targetExp,id);s.mu.Unlock()}
func (s *shield) originURL(t originTarget,asset string)string{return s.cfg.TunnelURL+"/device/"+url.PathEscape(t.DeviceID)+asset}

func (s *shield) servePlaylist(w http.ResponseWriter,r *http.Request,id string){t,err:=s.resolve(r.Context(),id);if err!=nil{http.Error(w,"channel origin unavailable",503);return};body,ct,status,err:=s.fetch(r.Context(),s.originURL(t,"/live.m3u8"),true);if err!=nil{s.invalidate(id);t,err=s.resolve(r.Context(),id);if err==nil{body,ct,status,err=s.fetch(r.Context(),s.originURL(t,"/live.m3u8"),true)}};if err!=nil{atomic.AddUint64(&s.originErrors,1);http.Error(w,"channel origin unavailable",502);return};body=rewritePlaylist(body,id);w.Header().Set("Content-Type","application/vnd.apple.mpegurl");w.Header().Set("Cache-Control","no-store");w.Header().Set("X-TV49East-Shield","ORIGIN");w.WriteHeader(status);if r.Method!=http.MethodHead{_,_=w.Write(body)};_ = ct}
func (s *shield) serveAsset(w http.ResponseWriter,r *http.Request,id,asset string){key:=id+"\n"+asset;if e,ok:=s.cache.get(key,time.Now());ok{atomic.AddUint64(&s.hits,1);writeEntry(w,r,e,"HIT");return};atomic.AddUint64(&s.misses,1);e,err:=s.singleflight(r.Context(),key,id,asset);if err!=nil{http.Error(w,"origin unavailable",502);return};writeEntry(w,r,e,"MISS")}
func (s *shield) singleflight(ctx context.Context,key,id,asset string)(cacheEntry,error){s.mu.Lock();if c,ok:=s.calls[key];ok{s.mu.Unlock();select{case <-c.done:return c.entry,c.err;case <-ctx.Done():return cacheEntry{},ctx.Err()}};c:=&call{done:make(chan struct{})};s.calls[key]=c;s.mu.Unlock();defer func(){s.mu.Lock();delete(s.calls,key);close(c.done);s.mu.Unlock()}();if e,ok:=s.cache.get(key,time.Now());ok{c.entry=e;return e,nil};t,err:=s.resolve(context.Background(),id);if err!=nil{c.err=err;return cacheEntry{},err};atomic.AddUint64(&s.originFetches,1);body,ct,_,err:=s.fetchIndependent(s.originURL(t,asset));if err!=nil{s.invalidate(id);t2,e2:=s.resolve(context.Background(),id);if e2==nil{body,ct,_,err=s.fetchIndependent(s.originURL(t2,asset))}};if err!=nil{atomic.AddUint64(&s.originErrors,1);c.err=err;return cacheEntry{},err};e:=cacheEntry{body:body,contentType:ct,expires:time.Now().Add(s.cfg.SegmentTTL)};s.cache.put(key,e);c.entry=e;return e,nil}
func (s *shield) fetch(ctx context.Context,raw string,playlist bool)([]byte,string,int,error){req,err:=http.NewRequestWithContext(ctx,http.MethodGet,raw,nil);if err!=nil{return nil,"",0,err};return s.doFetch(req,playlist)}
func (s *shield) fetchIndependent(raw string)([]byte,string,int,error){ctx,cancel:=context.WithTimeout(context.Background(),s.cfg.OriginTimeout);defer cancel();select{case s.originSem<-struct{}{}:defer func(){<-s.originSem}();case <-ctx.Done():return nil,"",0,ctx.Err()};req,err:=http.NewRequestWithContext(ctx,http.MethodGet,raw,nil);if err!=nil{return nil,"",0,err};return s.doFetch(req,false)}
func (s *shield) doFetch(req *http.Request,playlist bool)([]byte,string,int,error){resp,err:=s.client.Do(req);if err!=nil{return nil,"",0,err};defer resp.Body.Close();if resp.StatusCode<200||resp.StatusCode>=300{return nil,"",resp.StatusCode,fmt.Errorf("origin HTTP %d",resp.StatusCode)};limit:=s.cfg.MaxObjectBytes;if playlist{limit=8<<20};body,err:=io.ReadAll(io.LimitReader(resp.Body,limit+1));if err!=nil||int64(len(body))>limit{return nil,"",resp.StatusCode,errors.New("origin object too large")};return body,resp.Header.Get("Content-Type"),resp.StatusCode,nil}
func writeEntry(w http.ResponseWriter,r *http.Request,e cacheEntry,state string){w.Header().Set("Content-Type",mediaType(e.contentType,""));w.Header().Set("Cache-Control","public, max-age=30");w.Header().Set("X-TV49East-Shield",state);w.Header().Set("Content-Length",strconv.Itoa(len(e.body)));w.WriteHeader(200);if r.Method!=http.MethodHead{_,_=w.Write(e.body)}}
func mediaType(ct,p string)string{if ct!=""{return ct};switch strings.ToLower(path.Ext(p)){case ".m4s":return "video/iso.segment";case ".mp4":return "video/mp4";case ".ts":return "video/mp2t";case ".m3u8":return "application/vnd.apple.mpegurl"};return "application/octet-stream"}
func rewritePlaylist(body []byte,id string)[]byte{lines:=strings.Split(string(body),"\n");for i,line:=range lines{t:=strings.TrimSpace(line);if t==""||strings.HasPrefix(t,"#"){continue};if u,err:=url.Parse(t);err==nil&&u.IsAbs(){continue};if !strings.HasPrefix(t,"/"){t="/"+t};if validAsset(t){lines[i]="/channel/"+url.PathEscape(id)+t}};return []byte(strings.Join(lines,"\n"))}
func (s *shield) health(w http.ResponseWriter,r *http.Request){if r.Method!=http.MethodGet{http.Error(w,"method not allowed",405);return};b,n:=s.cache.stats();w.Header().Set("Content-Type","application/json");w.Header().Set("Cache-Control","no-store");_ = json.NewEncoder(w).Encode(map[string]any{"ok":true,"cache_bytes":b,"cache_entries":n,"time":time.Now().UTC()})}
func (s *shield) metrics(w http.ResponseWriter,r *http.Request){if r.Method!=http.MethodGet{http.Error(w,"method not allowed",405);return};b,n:=s.cache.stats();w.Header().Set("Content-Type","text/plain; version=0.0.4");fmt.Fprintf(w,"shield_requests_total %d\nshield_cache_hits_total %d\nshield_cache_misses_total %d\nshield_origin_fetches_total %d\nshield_origin_errors_total %d\nshield_target_refreshes_total %d\nshield_cache_bytes %d\nshield_cache_entries %d\n",atomic.LoadUint64(&s.requests),atomic.LoadUint64(&s.hits),atomic.LoadUint64(&s.misses),atomic.LoadUint64(&s.originFetches),atomic.LoadUint64(&s.originErrors),atomic.LoadUint64(&s.targetRefreshes),b,n)}
func securityHeaders(next http.Handler)http.Handler{return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){w.Header().Set("X-Content-Type-Options","nosniff");w.Header().Set("Referrer-Policy","no-referrer");next.ServeHTTP(w,r)})}

var _ = subtle.ConstantTimeCompare
