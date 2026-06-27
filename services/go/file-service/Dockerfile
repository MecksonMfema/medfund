# Build context is the services/go/ directory so the shared module is
# available alongside file-service. Matches the multi-stage pattern the
# other Go services use; the runtime stage adds wkhtmltopdf for the
# invoice PDF renderer (Debian slim, not Alpine — the Alpine community
# build of wkhtmltopdf has Qt/font issues with non-ASCII text).
FROM golang:1.23-alpine AS build
WORKDIR /workspace/file-service
COPY shared/ /workspace/shared/
COPY file-service/go.mod file-service/go.sum ./
RUN go mod download
COPY file-service/ .
RUN CGO_ENABLED=0 GOOS=linux go build -o /server ./cmd

FROM debian:bookworm-slim
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        wkhtmltopdf \
        fonts-dejavu-core \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /server .
ENV WKHTMLTOPDF_BIN=/usr/bin/wkhtmltopdf
EXPOSE 3003
ENTRYPOINT ["./server"]
