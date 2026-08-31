FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/tv49east ./...

FROM alpine:3.22
RUN addgroup -S tveast && adduser -S -G tveast tveast
WORKDIR /app
COPY --from=build /out/tv49east /app/tv49east
RUN mkdir -p /app/data && chown -R tveast:tveast /app
USER tveast
ENV CATALOG_LISTEN=:8790
ENV IPTV_ORG_PLAYLIST=https://iptv-org.github.io/iptv/index.m3u
ENV CATALOG_REFRESH_MINUTES=30
ENV CATALOG_TIMEOUT_SECONDS=20
ENV CATALOG_MAX_BYTES=33554432
ENV CREATOR_REGISTRY_FILE=/app/data/creators.json
EXPOSE 8790
ENTRYPOINT ["/app/tv49east"]
