module github.com/medfund/payment-gateway

go 1.23

require (
	github.com/gofiber/fiber/v2 v2.52.5
	github.com/google/uuid v1.5.0
	github.com/medfund/shared v0.0.0-00010101000000-000000000000
	github.com/segmentio/kafka-go v0.4.51
)

require (
	github.com/andybalholm/brotli v1.0.5 // indirect
	github.com/klauspost/compress v1.17.0 // indirect
	github.com/mattn/go-colorable v0.1.13 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/mattn/go-runewidth v0.0.15 // indirect
	github.com/pierrec/lz4/v4 v4.1.15 // indirect
	github.com/rivo/uniseg v0.2.0 // indirect
	github.com/valyala/bytebufferpool v1.0.0 // indirect
	github.com/valyala/fasthttp v1.51.0 // indirect
	github.com/valyala/tcplisten v1.0.0 // indirect
	golang.org/x/sys v0.15.0 // indirect
)

replace github.com/medfund/shared => ../shared
