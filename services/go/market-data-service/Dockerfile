FROM golang:1.23-alpine AS build
WORKDIR /workspace/market-data-service
COPY shared/ /workspace/shared/
COPY market-data-service/go.mod market-data-service/go.sum ./
RUN go mod download
COPY market-data-service/ .
RUN CGO_ENABLED=0 GOOS=linux go build -o /server ./cmd

FROM alpine:3.20
RUN apk --no-cache add ca-certificates
WORKDIR /app
COPY --from=build /server .
EXPOSE 3005
ENTRYPOINT ["./server"]
