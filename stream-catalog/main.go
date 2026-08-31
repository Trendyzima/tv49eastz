package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type server struct { playlistURL string; refresh time.Duration; timeout time.Duration; maxBytes int64; cache Catalog }

func main() {
	s := &server{playlistURL: env("IPTV_ORG_PLAYLIST", defaultPlaylistURL), refresh: time.Duration(envInt("CATALOG_REFRESH_MINUTES", 30))*time.Minute, timeout: time.Duration(envInt("CATALOG_TIMEOUT_SECONDS", 20))*time.Second, maxBytes: int64(envInt("CATALOG_MAX_BYTES", 32<<20))}
	if err := s.refreshCatalog(); err != nil { log.Printf("initial catalog refresh failed: %v", err) }
	go s.refreshLoop()
	mux := http.NewServeMux(); mux.HandleFunc("/health", s.health); mux.HandleFunc("/v1/catalog", s.catalog)
	addr := env("CATALOG_LISTEN", ":8790")
	log.Printf("TV 49 East channel catalog listening on %s", addr)
	log.Fatal((&http.Server{Addr:addr, Handler:securityHeaders(mux), ReadHeaderTimeout:5*time.Second, IdleTimeout:20*time.Second}).ListenAndServe())
}

func (s *server) refreshCatalog() error { c, err := fetchCatalog(context.Background(), s.playlistURL, s.timeout, s.maxBytes); if err != nil { return err }; s.cache=c; return nil }
func (s *server) refreshLoop(){ t:=time.NewTicker(s.refresh); defer t.Stop(); for range t.C { if err:=s.refreshCatalog(); err!=nil { log.Printf("catalog refresh failed; retaining last known-good catalog: %v",err) } } }
func (s *server) health(w http.ResponseWriter,_ *http.Request){ w.Header().Set("Content-Type","application/json"); json.NewEncoder(w).Encode(map[string]any{"ok":true,"channels":len(s.cache.Channels),"updated":s.cache.Updated}) }
func (s *server) catalog(w http.ResponseWriter,r *http.Request){ if r.Method!=http.MethodGet { http.Error(w,"method not allowed",405); return }; c:=s.cache; region:=strings.TrimSpace(r.URL.Query().Get("country")); group:=strings.TrimSpace(r.URL.Query().Get("group")); if region!=""||group!="" { filtered:=make([]Channel,0,len(c.Channels)); for _,ch:=range c.Channels { if region!=""&&!strings.EqualFold(ch.Country,region){continue}; if group!=""&&!strings.EqualFold(ch.Group,group){continue}; filtered=append(filtered,ch) }; c.Channels=filtered }; w.Header().Set("Content-Type","application/json"); w.Header().Set("Cache-Control","public, max-age=300"); json.NewEncoder(w).Encode(c) }
func securityHeaders(next http.Handler) http.Handler { return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){ w.Header().Set("X-Content-Type-Options","nosniff"); w.Header().Set("Referrer-Policy","no-referrer"); next.ServeHTTP(w,r) }) }
func env(k,d string)string{if v:=strings.TrimSpace(os.Getenv(k));v!=""{return v};return d}
func envInt(k string,d int)int{v,err:=strconv.Atoi(os.Getenv(k));if err!=nil{return d};return v}
