FROM --platform=linux/amd64 golang:1.25-alpine AS builder
WORKDIR /app

COPY go.mod ./
RUN go mod download all

COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o server main.go

FROM --platform=linux/amd64 alpine:3.19
WORKDIR /app
COPY --from=builder /app/server /app/server
ENTRYPOINT ["/app/server"]
