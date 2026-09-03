#!/bin/sh
set -eu

TLS_DIR=/run/tv49eastz
mkdir -p "$TLS_DIR"
umask 077

# Cloudflare terminates public HTTPS at the Worker. The gateway keeps its
# existing mTLS boundary internally: nginx is the only process that can reach
# the Go listener and presents a short-lived client certificate to it.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=tv49eastz-container-ca' \
  -keyout "$TLS_DIR/ca.key" -out "$TLS_DIR/ca.pem" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes \
  -subj '/CN=stream-gateway' \
  -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.csr" >/dev/null 2>&1
openssl x509 -req -in "$TLS_DIR/server.csr" -CA "$TLS_DIR/ca.pem" -CAkey "$TLS_DIR/ca.key" \
  -CAcreateserial -days 1 -sha256 -out "$TLS_DIR/server.pem" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes \
  -subj '/CN=cloudflare-worker-proxy' \
  -keyout "$TLS_DIR/client.key" -out "$TLS_DIR/client.csr" >/dev/null 2>&1
openssl x509 -req -in "$TLS_DIR/client.csr" -CA "$TLS_DIR/ca.pem" -CAkey "$TLS_DIR/ca.key" \
  -CAcreateserial -days 1 -sha256 -out "$TLS_DIR/client.pem" >/dev/null 2>&1

cat > /etc/nginx/nginx.conf <<EOF
worker_processes 1;
pid /run/nginx/nginx.pid;
error_log /dev/stderr warn;
events { worker_connections 4096; }
http {
  access_log /dev/stdout;
  upstream gateway { server 127.0.0.1:8787; }
  server {
    listen 8080;
    server_name _;
    client_max_body_size 1m;
    proxy_http_version 1.1;
    proxy_ssl_server_name on;
    proxy_ssl_verify off;
    proxy_ssl_certificate $TLS_DIR/client.pem;
    proxy_ssl_certificate_key $TLS_DIR/client.key;
    location / {
      proxy_pass https://gateway;
      proxy_set_header Host \$host;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_read_timeout 120s;
      proxy_send_timeout 120s;
      proxy_buffering off;
    }
  }
}
EOF

export GATEWAY_LISTEN=:8787
export GATEWAY_TLS_CERT_FILE="$TLS_DIR/server.pem"
export GATEWAY_TLS_KEY_FILE="$TLS_DIR/server.key"
export GATEWAY_CLIENT_CA_FILE="$TLS_DIR/ca.pem"

/usr/local/bin/stream-gateway &
gateway_pid=$!
trap 'kill "$gateway_pid" 2>/dev/null || true' INT TERM EXIT

# Give the TLS listener a moment to bind before nginx starts accepting traffic.
i=0
while [ "$i" -lt 50 ]; do
  if kill -0 "$gateway_pid" 2>/dev/null; then
    if nc -z 127.0.0.1 8787 2>/dev/null; then break; fi
  else
    wait "$gateway_pid"; exit 1
  fi
  i=$((i+1)); sleep 0.1
done

exec nginx -g 'daemon off;'
