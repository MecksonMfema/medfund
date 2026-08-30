module github.com/medfund/market-data-service

go 1.25.0

require (
	github.com/gofiber/fiber/v2 v2.52.5
	github.com/medfund/shared v0.0.0-00010101000000-000000000000
	github.com/robfig/cron/v3 v3.0.1
	github.com/segmentio/kafka-go v0.4.51
)

require (
	github.com/andybalholm/brotli v1.0.5 // indirect
	github.com/clipperhouse/uax29/v2 v2.2.0 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/klauspost/compress v1.18.6 // indirect
	github.com/mattn/go-colorable v0.1.14 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/mattn/go-runewidth v0.0.23 // indirect
	github.com/pierrec/lz4/v4 v4.1.15 // indirect
	github.com/valyala/bytebufferpool v1.0.0 // indirect
	github.com/valyala/fasthttp v1.51.0 // indirect
	github.com/valyala/tcplisten v1.0.0 // indirect
	golang.org/x/sys v0.44.0 // indirect
)

replace github.com/medfund/shared => ../shared
