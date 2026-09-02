FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/cdn-edge .

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/cdn-edge /cdn-edge
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/cdn-edge"]
