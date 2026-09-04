import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const cors = {"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, content-type","Access-Control-Allow-Methods":"GET,OPTIONS"};
let cachedToken = "";
let cachedUntil = 0;

async function spotifyToken() {
  if (cachedToken && Date.now() < cachedUntil) return cachedToken;
  const id = Deno.env.get("SPOTIFY_CLIENT_ID") ?? "";
  const secret = Deno.env.get("SPOTIFY_CLIENT_SECRET") ?? "";
  if (!id || !secret) throw new Error("Spotify server credentials are not configured");
  const basic = btoa(`${id}:${secret}`);
  const r = await fetch("https://accounts.spotify.com/api/token", {method:"POST",headers:{Authorization:`Basic ${basic}`,"Content-Type":"application/x-www-form-urlencoded"},body:"grant_type=client_credentials"});
  if (!r.ok) throw new Error(`Spotify token ${r.status}`);
  const j = await r.json(); cachedToken = j.access_token; cachedUntil = Date.now() + Math.max(60, (j.expires_in ?? 3600) - 60) * 1000; return cachedToken;
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("", {headers:cors});
  try {
    const url = new URL(req.url);
    const q = (url.searchParams.get("q") ?? "").trim();
    if (!q || q.length > 120) return new Response(JSON.stringify({error:"q is required"}), {status:400,headers:{...cors,"Content-Type":"application/json"}});
    const token = await spotifyToken();
    const api = new URL("https://api.spotify.com/v1/search");
    api.searchParams.set("q", q); api.searchParams.set("type", "track"); api.searchParams.set("limit", "10");
    const r = await fetch(api, {headers:{Authorization:`Bearer ${token}`}});
    const body = await r.text();
    return new Response(body, {status:r.status,headers:{...cors,"Content-Type":"application/json"}});
  } catch (e) {
    return new Response(JSON.stringify({error:e instanceof Error ? e.message : "Spotify unavailable"}), {status:500,headers:{...cors,"Content-Type":"application/json"}});
  }
});
