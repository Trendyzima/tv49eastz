FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /out/origin-shield .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/origin-shield /origin-shield
EXPOSE 8795
USER nonroot:nonroot
ENTRYPOINT ["/origin-shield"]
