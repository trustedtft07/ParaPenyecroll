FROM golang:1.22-alpine AS builder

WORKDIR /src
COPY go.mod main.go ./
RUN go mod tidy && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o /videofeed-server main.go

FROM alpine:3.20

RUN apk add --no-cache \
    ffmpeg \
    libva \
    intel-media-driver \
    libva-intel-driver \
    ca-certificates \
    tzdata

WORKDIR /app
COPY --from=builder /videofeed-server /app/videofeed-server

ENV DATA_DIR=/data
ENV PORT=5050
EXPOSE 5050

ENTRYPOINT ["/app/videofeed-server"]
