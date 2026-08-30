package main

import (
 "crypto/hmac"
 "crypto/rand"
 "crypto/sha256"
 "encoding/base64"
 "encoding/hex"
 "encoding/json"
 "errors"
 "io"
 "log"
 "net/http"
 "net/url"
 "os"
 "strconv"
 "strings"
 "sync"
 "sync/atomic"
 "time"
)

type Config struct { Listen, Upstream, APIKey, CapabilityKey string; MaxSessions, RatePerMinute int; RequestTimeout time.Duration }
type Session struct { ID, UserID, DeviceID, ChannelID, StreamID string; IssuedAt, Expires time.Time }
type limiter struct { mu sync.Mutex; window time.Time; count int }
type Gateway struct { cfg Config; client *http.Client; sessions sync.Map; limits sync.Map; sessionCount atomic.Int64; requests atomic.Uint64; bytes atomic.Uint64; denied atomic.Uint64; policy AuthorizationPolicy }

func main(){
 cfg:=Config{Listen:env("GATEWAY_LISTEN",":8787"),Upstream:strings.TrimRight(env("TAP_UPSTREAM","http://127.0.0.1:8786"),"/"),APIKey:os.Getenv("GATEWAY_API_KEY"),CapabilityKey:os.Getenv("GATEWAY_CAPABILITY_KEY"),MaxSessions:envInt("MAX_SESSIONS",25),RatePerMinute:envInt("RATE_PER_MINUTE",120),RequestTimeout:time.Duration(envInt("UPSTREAM_TIMEOUT_SECONDS",15))*time.Second}
 if cfg.APIKey==""||cfg.CapabilityKey==""{log.Fatal("GATEWAY_API_KEY and GATEWAY_CAPABILITY_KEY must be set")}
 u,e:=url.Parse(cfg.Upstream);if e!=nil||u.Host==""||(u.Scheme!="http"&&u.Scheme!="https"){log.Fatal("invalid TAP_UPSTREAM")}
 g:=&Gateway{cfg:cfg,client:&http.Client{Timeout:cfg.RequestTimeout,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}}
 mux:=http.NewServeMux();mux.HandleFunc("/health",g.health);mux.HandleFunc("/metrics",g.metrics);mux.HandleFunc("/v1/session",g.createSession);mux.HandleFunc("/stream/",g.stream)
 srv:=&http.Server{Addr:cfg.Listen,Handler:g.middleware(mux),ReadHeaderTimeout:5*time.Second,IdleTimeout:30*time.Second};log.Printf("stream-gateway listening on %s; upstream=%s",cfg.Listen,cfg.Upstream);log.Fatal(srv.ListenAndServe())
}

func(g *Gateway)middleware(next http.Handler)http.Handler{return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
 if r.Method==http.MethodOptions{w.WriteHeader(204);return};if r.URL.Path=="/health"{next.ServeHTTP(w,r);return}
 if r.URL.Path=="/v1/session"{if r.Method!=http.MethodGet{g.denied.Add(1);http.Error(w,"method not allowed",405);return};if !g.auth(r){g.denied.Add(1);http.Error(w,"unauthorized",401);return};if !g.allowRate(r.RemoteAddr){g.denied.Add(1);http.Error(w,"rate limit exceeded",429);return};next.ServeHTTP(w,r);return}
 if !strings.HasPrefix(r.URL.Path,"/stream/")||r.Method!=http.MethodGet{g.denied.Add(1);http.Error(w,"method not allowed",405);return};if !g.allowRate(r.RemoteAddr){g.denied.Add(1);http.Error(w,"rate limit exceeded",429);return};next.ServeHTTP(w,r)
})}
func(g *Gateway)auth(r *http.Request)bool{return constantTime(r.Header.Get("Authorization"),"Bearer "+g.cfg.APIKey)}
func constantTime(a,b string)bool{if len(a)!=len(b){return false};return subtleEqual([]byte(a),[]byte(b))}
func subtleEqual(a,b []byte)bool{var x byte;for i:=range a{x|=a[i]^b[i]};return x==0}
func(g *Gateway)allowRate(addr string)bool{host:=addr;if i:=strings.LastIndex(addr,":");i>0{host=addr[:i]};v,_:=g.limits.LoadOrStore(host,&limiter{window:time.Now()});l:=v.(*limiter);l.mu.Lock();defer l.mu.Unlock();now:=time.Now();if now.Sub(l.window)>=time.Minute{l.window=now;l.count=0};if l.count>=g.cfg.RatePerMinute{return false};l.count++;return true}

func(g *Gateway)createSession(w http.ResponseWriter,r *http.Request){if g.sessionCount.Load()>=int64(g.cfg.MaxSessions){http.Error(w,"session limit reached",429);return};p,e:=authenticate(r,g.cfg.APIKey);if e!=nil{http.Error(w,"unauthorized",401);return};channelID:=strings.TrimSpace(r.URL.Query().Get("channel_id"));streamID:=strings.TrimSpace(r.URL.Query().Get("stream_id"));if channelID==""||streamID==""{http.Error(w,"channel_id and stream_id are required",400);return};if e=g.policy.AuthorizeStream(p,channelID,streamID);e!=nil{http.Error(w,"forbidden",403);return};token,e:=opaque(24);if e!=nil{http.Error(w,"internal error",500);return};ttl:=15*time.Minute;now:=time.Now().UTC();s:=Session{ID:token,UserID:p.UserID,DeviceID:p.DeviceID,ChannelID:channelID,StreamID:streamID,IssuedAt:now,Expires:now.Add(ttl)};g.sessions.Store(token,s);g.sessionCount.Add(1);w.Header().Set("Content-Type","application/json");w.Header().Set("Cache-Control","no-store");json.NewEncoder(w).Encode(map[string]any{"session":token,"expires_in":int(ttl.Seconds()),"playlist":"/stream/"+token+"/index.m3u8"})}
func(g *Gateway)session(id string)(Session,bool){v,ok:=g.sessions.Load(id);if !ok{return Session{},false};s:=v.(Session);if !sessionValid(s,time.Now().UTC()){if g.sessions.CompareAndDelete(id,s){g.sessionCount.Add(-1)};return Session{},false};return s,true}

func(g *Gateway)stream(w http.ResponseWriter,r *http.Request){p:=strings.Split(strings.TrimPrefix(r.URL.Path,"/stream/"),"/");if len(p)<2||p[0]==""{http.Error(w,"not found",404);return};s,ok:=g.session(p[0]);if !ok{http.Error(w,"invalid or expired session",401);return};if len(p)==2&&p[1]=="index.m3u8"{g.playlist(w,r,s);return};if len(p)>=3&&p[1]=="resource"{g.resource(w,r,p[0],strings.Join(p[2:],"/"));return};http.Error(w,"not found",404)}

func(g *Gateway)playlist(w http.ResponseWriter,r *http.Request,s Session){body,status,e:=g.upstreamGET(r,"/live.m3u8");if e!=nil||status<200||status>=300{http.Error(w,"upstream unavailable",502);return};if len(body)>2<<20{http.Error(w,"playlist too large",502);return};lines:=strings.Split(string(body),"\n");for i,line:=range lines{lines[i]=g.rewritePlaylistLine(line,s)};out:=strings.Join(lines,"\n");w.Header().Set("Content-Type","application/vnd.apple.mpegurl");w.Header().Set("Cache-Control","no-store");w.Header().Set("X-Content-Type-Options","nosniff");w.WriteHeader(status);n,_:=io.WriteString(w,out);g.bytes.Add(uint64(n));g.requests.Add(1)}
func(g *Gateway)rewritePlaylistLine(line string,s Session)string{trim:=strings.TrimSpace(line);if trim==""{return line};if strings.HasPrefix(trim,"#"){const key="URI=\"";if i:=strings.Index(line,key);i>=0{start:=i+len(key);if end:=strings.Index(line[start:],"\"");end>=0{raw:=line[start:start+end];if u,e:=url.Parse(raw);e==nil&&!u.IsAbs()&&!strings.HasPrefix(raw,"//")&&!strings.Contains(u.Path,".."){cap,e:=g.signCapability(s.ID,s.StreamID,u.RequestURI(),s.Expires);if e==nil{return line[:start]+"/stream/"+s.ID+"/resource/"+cap+line[start+end:]}}}};return "# UNSAFE_RESOURCE_REJECTED"};u,e:=url.Parse(trim);if e!=nil||u.IsAbs()||strings.HasPrefix(trim,"//")||u.Path==""||strings.Contains(u.Path,".."){return "# UNSAFE_RESOURCE_REJECTED"};cap,e:=g.signCapability(s.ID,s.StreamID,u.RequestURI(),s.Expires);if e!=nil{return "# UNSAFE_RESOURCE_REJECTED"};prefix:=line[:len(line)-len(strings.TrimLeft(line," \t"))];return prefix+"/stream/"+s.ID+"/resource/"+cap}

func(g *Gateway)signCapability(sessionID,streamID,path string,expires time.Time)(string,error){if sessionID==""||streamID==""||path==""||!strings.HasPrefix(path,"/"){return "",errors.New("invalid capability")};payload:=sessionID+"\x00"+streamID+"\x00"+strconv.FormatInt(expires.Unix(),10)+"\x00"+path;mac:=hmac.New(sha256.New,[]byte(g.cfg.CapabilityKey));_,_=mac.Write([]byte(payload));sig:=hex.EncodeToString(mac.Sum(nil));data:=base64.RawURLEncoding.EncodeToString([]byte(payload+"\x00"+sig));return data,nil}
func(g *Gateway)verifyCapability(token,sessionID,streamID string,expires time.Time)(string,bool){raw,e:=base64.RawURLEncoding.DecodeString(token);if e!=nil||len(raw)>8192{return "",false};parts:=strings.Split(string(raw),"\x00");if len(parts)!=5||parts[0]!=sessionID||parts[1]!=streamID{return "",false};exp,e:=strconv.ParseInt(parts[2],10,64);if e!=nil||exp!=expires.Unix()||time.Now().Unix()>=exp{return "",false};path:=parts[3];mac:=hmac.New(sha256.New,[]byte(g.cfg.CapabilityKey));_,_=mac.Write([]byte(strings.Join(parts[:4],"\x00")));expected:=hex.EncodeToString(mac.Sum(nil));if !constantTime(parts[4],expected){return "",false};u,e:=url.Parse(path);if e!=nil||u.IsAbs()||u.Host!=""||strings.HasPrefix(path,"//")||u.Path==""||strings.Contains(u.Path,".."){return "",false};return u.RequestURI(),true}
func(g *Gateway)resource(w http.ResponseWriter,r *http.Request,sessionID,token string){s,ok:=g.session(sessionID);if !ok{http.Error(w,"invalid or expired session",401);return};path,ok:=g.verifyCapability(token,sessionID,s.StreamID,s.Expires);if !ok{http.Error(w,"invalid resource",403);return};body,status,e:=g.upstreamGET(r,path);if e!=nil||status<200||status>=300{http.Error(w,"upstream unavailable",502);return};if len(body)>32<<20{http.Error(w,"resource too large",502);return};ct:=http.DetectContentType(body);if strings.HasSuffix(strings.ToLower(path),".m3u8"){ct="application/vnd.apple.mpegurl"};w.Header().Set("Content-Type",ct);w.Header().Set("Cache-Control","no-store");w.Header().Set("X-Content-Type-Options","nosniff");w.WriteHeader(status);n,_:=w.Write(body);g.bytes.Add(uint64(n));g.requests.Add(1)}
func(g *Gateway)upstreamGET(r *http.Request,path string)([]byte,int,error){req,e:=http.NewRequestWithContext(r.Context(),http.MethodGet,g.cfg.Upstream+path,nil);if e!=nil{return nil,0,e};resp,e:=g.client.Do(req);if e!=nil{return nil,0,e};defer resp.Body.Close();b,e:=io.ReadAll(io.LimitReader(resp.Body,32<<20+1));return b,resp.StatusCode,e}
func(g *Gateway)health(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","application/json");json.NewEncoder(w).Encode(map[string]any{"ok":true,"active_sessions":g.sessionCount.Load()})}
func(g *Gateway)metrics(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","text/plain; version=0.0.4");io.WriteString(w,"gateway_requests_total "+strconv.FormatUint(g.requests.Load(),10)+"\n"+"gateway_denied_total "+strconv.FormatUint(g.denied.Load(),10)+"\n"+"gateway_bytes_total "+strconv.FormatUint(g.bytes.Load(),10)+"\n"+"gateway_sessions "+strconv.FormatInt(g.sessionCount.Load(),10)+"\n")}
func opaque(n int)(string,error){b:=make([]byte,n);if _,e:=rand.Read(b);e!=nil{return "",e};return hex.EncodeToString(b),nil}
func env(k,d string)string{if v:=os.Getenv(k);v!=""{return v};return d}
func envInt(k string,d int)int{v:=env(k,"");if v==""{return d};n,e:=strconv.Atoi(v);if e!=nil||n<1{return d};return n}
