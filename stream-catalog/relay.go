package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	maxPlaylistBytes = 8 << 20
	maxRedirects = 3
	assetTTL = 5 * time.Minute
)

var hlsURI = regexp.MustCompile(`URI="([^"]+)"`)

func relayClient(timeout time.Duration) *http.Client {
	transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address); if err != nil { return nil, err }
		ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host); if err != nil { return nil, err }
		for _, ip := range ips { if !isPublicIP(ip) { continue }; d := net.Dialer{Timeout: 8*time.Second}; if c, e := d.DialContext(ctx, network, net.JoinHostPort(ip.String(), port)); e == nil { return c, nil } }
		return nil, errors.New("upstream resolves only to non-public addresses")
	}}
	return &http.Client{Timeout: timeout, Transport: transport, CheckRedirect: func(req *http.Request, via []*http.Request) error { if len(via) >= maxRedirects || req.URL.Scheme != "https" { return errors.New("relay redirect rejected") }; return nil }}
}
func isPublicIP(ip net.IP) bool { if ip==nil||ip.IsLoopback()||ip.IsUnspecified()||ip.IsLinkLocalUnicast()||ip.IsLinkLocalMulticast(){return false}; if ip4:=ip.To4();ip4!=nil{return !(ip4[0]==10||ip4[0]==127||(ip4[0]==172&&ip4[1]>=16&&ip4[1]<=31)||(ip4[0]==192&&ip4[1]==168)||(ip4[0]==169&&ip4[1]==254))}; return !(ip[0]&0xfe==0xfc||ip.Equal(net.IPv6loopback)) }
func encodeRelayURL(raw string) string { return base64.RawURLEncoding.EncodeToString([]byte(raw)) }
func decodeRelayURL(raw string)(string,bool){b,e:=base64.RawURLEncoding.DecodeString(raw);if e!=nil{return "",false};return string(b),true}
func capabilitySignature(secret,channelID,encodedURL string,exp int64) string {mac:=hmac.New(sha256.New,[]byte(secret));_,_=mac.Write([]byte(channelID));_,_=mac.Write([]byte("\n"));_,_=mac.Write([]byte(encodedURL));_,_=mac.Write([]byte("\n"));_,_=mac.Write([]byte(strconv.FormatInt(exp,10)));return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))}
func validCapability(secret,channelID,encodedURL,sig string,exp int64,now time.Time) bool {if exp<now.Unix()||exp>now.Add(assetTTL).Unix(){return false};want:=capabilitySignature(secret,channelID,encodedURL,exp);return hmac.Equal([]byte(want),[]byte(sig))}

func (s *server) relay(w http.ResponseWriter,r *http.Request){
	if r.Method!=http.MethodGet&&r.Method!=http.MethodHead{http.Error(w,"method not allowed",405);return}
	id:=strings.TrimSpace(r.URL.Query().Get("id"));if id==""{http.Error(w,"missing channel id",400);return}
	ch,ok:=s.findChannel(id);if !ok||!ch.Relay{http.Error(w,"channel is not relayable",404);return}
	s.serveUpstream(w,r,ch,ch.Stream,true)
}

func (s *server) relayAsset(w http.ResponseWriter,r *http.Request){
	if r.Method!=http.MethodGet&&r.Method!=http.MethodHead{http.Error(w,"method not allowed",405);return}
	id:=strings.TrimSpace(r.URL.Query().Get("id"));encoded:=strings.TrimSpace(r.URL.Query().Get("u"));sig:=strings.TrimSpace(r.URL.Query().Get("sig"));exp,err:=strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("exp")),10,64)
	if id==""||encoded==""||sig==""||err!=nil||!validCapability(s.relaySecret,id,encoded,sig,exp,time.Now()){http.Error(w,"invalid or expired relay capability",403);return}
	raw,ok:=decodeRelayURL(encoded);if !ok{http.Error(w,"invalid relay asset",400);return}
	ch,ok:=s.findChannel(id);if !ok||!ch.Relay{http.Error(w,"channel is not relayable",404);return}
	if strings.EqualFold(ch.Source,"fadcam") { if !validFadCamPath(raw){http.Error(w,"invalid FadCam asset",400);return}; s.serveFadCamPath(w,r,ch,raw); return }
	resolved,err:=url.Parse(raw);if err!=nil||resolved.Scheme!="https"||resolved.Host==""{http.Error(w,"relay asset must be HTTPS",400);return}
	s.serveUpstream(w,r,ch,resolved.String(),false)
}

func (s *server) serveUpstream(w http.ResponseWriter,r *http.Request,ch Channel,raw string,rewrite bool){
	if strings.EqualFold(ch.Source,"fadcam") { s.serveFadCamPath(w,r,ch,ch.StreamPath); return }
	ctx,cancel:=context.WithTimeout(r.Context(),s.timeout);defer cancel();req,err:=http.NewRequestWithContext(ctx,r.Method,raw,nil)
	if err!=nil||req.URL.Scheme!="https"||req.URL.Host==""{http.Error(w,"invalid upstream URL",502);return};req.Header.Set("User-Agent","TV49East-Relay/1.0")
	resp,err:=relayClient(s.timeout).Do(req);if err!=nil{http.Error(w,"upstream unavailable",502);return};defer resp.Body.Close();if resp.StatusCode<200||resp.StatusCode>=300{http.Error(w,fmt.Sprintf("upstream returned HTTP %d",resp.StatusCode),502);return}
	contentType:=resp.Header.Get("Content-Type");isPlaylist:=rewrite&&(strings.Contains(strings.ToLower(contentType),"mpegurl")||strings.Contains(strings.ToLower(contentType),"m3u8")||strings.HasSuffix(strings.ToLower(req.URL.Path),".m3u8"));if !isPlaylist{copyRelayHeaders(w,resp);w.WriteHeader(resp.StatusCode);if r.Method!=http.MethodHead{_,_=io.Copy(w,resp.Body)};return}
	body,err:=io.ReadAll(io.LimitReader(resp.Body,maxPlaylistBytes+1));if err!=nil||int64(len(body))>maxPlaylistBytes{http.Error(w,"playlist too large",502);return};playlist:=rewriteHLS(string(body),req.URL,ch.ID,s.relaySecret);w.Header().Set("Content-Type","application/vnd.apple.mpegurl");w.Header().Set("Cache-Control","no-store");w.WriteHeader(200);if r.Method!=http.MethodHead{_,_=io.WriteString(w,playlist)}
}

func tunnelBase() (string,error){base:=strings.TrimRight(strings.TrimSpace(getenv("TUNNEL_PROXY_BASE_URL","")),"/");if base==""{return "",errors.New("TUNNEL_PROXY_BASE_URL is required for FadCam relay")};u,err:=url.Parse(base);if err!=nil||u.Scheme!="http"||u.Host==""||u.User!=nil||u.RawQuery!=""||u.Fragment!=""{return "",errors.New("invalid TUNNEL_PROXY_BASE_URL")};return base,nil}
func validFadCamPath(raw string) bool {if raw==""||!strings.HasPrefix(raw,"/")||strings.ContainsAny(raw,"\x00\r\n")||strings.Contains(raw,"..")||strings.HasPrefix(raw,"//"){return false};return raw=="/live.m3u8"||strings.HasPrefix(raw,"/hls/")||strings.HasPrefix(raw,"/audio/")||strings.HasSuffix(strings.ToLower(raw),".m3u8")||strings.HasSuffix(strings.ToLower(raw),".m4s")||strings.HasSuffix(strings.ToLower(raw),".mp4")||strings.HasSuffix(strings.ToLower(raw),".ts")}

func fadCamOrigin(s *server, id string) (string,string,bool) { for _,c:=range s.creator.list(){if c.ID==id&&strings.EqualFold(c.Source,"fadcam"){p:=c.StreamPath;if p==""{p="/live.m3u8"};return c.DeviceID,p,true}};return "","",false }
func (s *server) serveFadCamPath(w http.ResponseWriter,r *http.Request,ch Channel,rawPath string){
	deviceID,path:=ch.ID,ch.StreamPath; if d,p,ok:=fadCamOrigin(s,ch.ID);ok{deviceID=d;path=p}; if rawPath!="/live.m3u8" {path=rawPath}
	if !validFadCamPath(path){http.Error(w,"invalid FadCam asset",400);return};base,err:=tunnelBase();if err!=nil{http.Error(w,"FadCam tunnel not configured",503);return}
	endpoint:=base+"/device/"+url.PathEscape(deviceID)+path;ctx,cancel:=context.WithTimeout(r.Context(),s.timeout);defer cancel();req,err:=http.NewRequestWithContext(ctx,http.MethodGet,endpoint,nil);if err!=nil{http.Error(w,"invalid tunnel request",502);return};req.Header.Set("User-Agent","TV49East-FadCam-Relay/1.0")
	resp,err:=http.DefaultClient.Do(req);if err!=nil{http.Error(w,"FadCam origin unavailable",502);return};defer resp.Body.Close();if resp.StatusCode<200||resp.StatusCode>=300{http.Error(w,"FadCam origin unavailable",502);return}
	ct:=resp.Header.Get("Content-Type");if strings.HasSuffix(strings.ToLower(path),".m3u8"){ct="application/vnd.apple.mpegurl"};if ct!=""{w.Header().Set("Content-Type",ct)};w.Header().Set("Cache-Control","no-store");w.Header().Set("X-TV49East-Origin","FadCam-Tunnel");w.WriteHeader(resp.StatusCode)
	if r.Method==http.MethodHead{return};if strings.HasSuffix(strings.ToLower(path),".m3u8"){body,err:=io.ReadAll(io.LimitReader(resp.Body,maxPlaylistBytes+1));if err!=nil||len(body)>maxPlaylistBytes{return};out:=rewriteFadCamHLS(string(body),ch.ID,s.relaySecret);_,_=io.WriteString(w,out);return};_,_=io.Copy(w,resp.Body)
}
func rewriteFadCamHLS(playlist,channelID,secret string) string {rewrite:=func(raw string)string{raw=strings.TrimSpace(raw);if raw==""{return raw};if u,err:=url.Parse(raw);err==nil&&u.IsAbs(){return "# UNSAFE_RESOURCE_REJECTED"};if !strings.HasPrefix(raw,"/"){raw="/"+raw};if !validFadCamPath(raw){return "# UNSAFE_RESOURCE_REJECTED"};enc:=encodeRelayURL(raw);exp:=time.Now().Add(assetTTL).Unix();sig:=capabilitySignature(secret,channelID,enc,exp);return "/v1/relay-asset?id="+url.QueryEscape(channelID)+"&u="+url.QueryEscape(enc)+"&exp="+strconv.FormatInt(exp,10)+"&sig="+url.QueryEscape(sig)};playlist=hlsURI.ReplaceAllStringFunc(playlist,func(match string)string{parts:=hlsURI.FindStringSubmatch(match);if len(parts)!=2{return match};return strings.Replace(match,parts[1],rewrite(parts[1]),1)});lines:=strings.Split(playlist,"\n");for i,line:=range lines{t:=strings.TrimSpace(line);if t==""||strings.HasPrefix(t,"#"){continue};lines[i]=rewrite(t)};return strings.Join(lines,"\n")}
func rewriteHLS(playlist string,base *url.URL,channelID,secret string) string {rewriteURI:=func(raw string)string{target,err:=url.Parse(strings.TrimSpace(raw));if err!=nil{return raw};target=base.ResolveReference(target);if target.Scheme!="https"||target.Host==""{return raw};encoded:=encodeRelayURL(target.String());exp:=time.Now().Add(assetTTL).Unix();sig:=capabilitySignature(secret,channelID,encoded,exp);return "/v1/relay-asset?id="+url.QueryEscape(channelID)+"&u="+url.QueryEscape(encoded)+"&exp="+strconv.FormatInt(exp,10)+"&sig="+url.QueryEscape(sig)};playlist=hlsURI.ReplaceAllStringFunc(playlist,func(match string)string{parts:=hlsURI.FindStringSubmatch(match);if len(parts)!=2{return match};return strings.Replace(match,parts[1],rewriteURI(parts[1]),1)});lines:=strings.Split(playlist,"\n");for i,line:=range lines{trimmed:=strings.TrimSpace(line);if trimmed==""||strings.HasPrefix(trimmed,"#"){continue};lines[i]=rewriteURI(trimmed)};return strings.Join(lines,"\n")}
func copyRelayHeaders(w http.ResponseWriter,resp *http.Response){for _,key:=range []string{"Content-Type","Content-Length","Content-Range","Accept-Ranges","ETag","Last-Modified"}{if value:=resp.Header.Get(key);value!=""{w.Header().Set(key,value)}};w.Header().Set("Cache-Control","no-store")}
