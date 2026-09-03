# syntax=docker/dockerfile:1
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o /out/stream-gateway .

FROM alpine:3.22
RUN apk add --no-cache nginx openssl ca-certificates
COPY --from=build /out/stream-gateway /usr/local/bin/stream-gateway
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh && mkdir -p /run/tv49eastz /run/nginx /var/log/nginx
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
