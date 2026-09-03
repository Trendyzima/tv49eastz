#!/usr/bin/env node

const base = (process.env.RELAY_URL || "").replace(/\/$/, "");
const stream = process.env.STREAM_ID || "";
const ticket = process.env.TICKET || "";
const path = process.env.PATH_TO_TEST || "/live.m3u8";
const requests = Number(process.env.REQUESTS || 1000);
const concurrency = Math.max(1, Number(process.env.CONCURRENCY || 50));

if (!base || !stream || !ticket) {
  console.error("Set RELAY_URL, STREAM_ID and TICKET.");
  process.exit(2);
}

const target = `${base}/v1/relay?id=${encodeURIComponent(stream)}&ticket=${encodeURIComponent(ticket)}&path=${encodeURIComponent(path)}`;
let completed = 0;
let failed = 0;
let bytes = 0;
const latencies = [];

async function one() {
  const started = performance.now();
  try {
    const response = await fetch(target, { headers: { accept: "*/*" } });
    const body = await response.arrayBuffer();
    bytes += body.byteLength;
    if (!response.ok) failed++;
  } catch {
    failed++;
  } finally {
    completed++;
    latencies.push(performance.now() - started);
  }
}

const started = performance.now();
let cursor = 0;
async function worker() {
  while (true) {
    const index = cursor++;
    if (index >= requests) return;
    await one();
  }
}

await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, worker));
latencies.sort((a, b) => a - b);
const elapsed = (performance.now() - started) / 1000;
const percentile = (p) => latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))] || 0;

console.log(JSON.stringify({
  target: `${base}/v1/relay`,
  path,
  requests: completed,
  failed,
  bytes,
  seconds: Number(elapsed.toFixed(3)),
  rps: Number((completed / elapsed).toFixed(2)),
  p50_ms: Number(percentile(0.50).toFixed(2)),
  p95_ms: Number(percentile(0.95).toFixed(2)),
  p99_ms: Number(percentile(0.99).toFixed(2)),
}, null, 2));
