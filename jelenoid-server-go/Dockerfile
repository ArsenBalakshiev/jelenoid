FROM golang:1.24-alpine AS builder
WORKDIR /build

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -o jelenoid-server ./cmd/server

FROM alpine:3.21
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/jelenoid-server .

USER appuser

EXPOSE 4444

ENTRYPOINT ["./jelenoid-server"]