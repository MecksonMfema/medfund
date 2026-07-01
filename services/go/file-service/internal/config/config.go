package config

import "os"

type Config struct {
	Port            string
	S3Bucket        string
	S3Region        string
	AWSAccessKey    string
	AWSSecretKey    string
	MaxUploadSizeMB string

	// MinIO (local dev S3-compatible store). Empty MinIOEndpoint disables
	// the real client and the service falls back to MockStorage.
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinIOBucket    string
	MinIOUseSSL    string

	// Kafka — empty KafkaBrokers disables the InvoiceIssued consumer.
	KafkaBrokers string

	// Path to the wkhtmltopdf binary. The PDF renderer execs this with
	// HTML on stdin and reads the resulting PDF from stdout. Empty value
	// falls back to a stub renderer that returns "%PDF-1.4 stub" — same
	// as the legacy export.GeneratePDF behaviour — so the service can
	// boot in test/dev environments without the binary installed.
	WkhtmltopdfBin string

	// Base URL of the contributions-service used to fetch the full
	// render payload (invoice + statement + contributions) at PDF
	// render time. The endpoint is `/api/v1/internal/invoices/{id}/render-payload`
	// and is permitted without JWT but still requires X-Tenant-ID.
	ContributionsBaseURL string
}

func Load() *Config {
	return &Config{
		Port:            getEnv("PORT", "3003"),
		S3Bucket:        getEnv("S3_BUCKET", ""),
		S3Region:        getEnv("S3_REGION", "us-east-1"),
		AWSAccessKey:    getEnv("AWS_ACCESS_KEY", ""),
		AWSSecretKey:    getEnv("AWS_SECRET_KEY", ""),
		MaxUploadSizeMB: getEnv("MAX_UPLOAD_SIZE_MB", "50"),

		MinIOEndpoint:  getEnv("MINIO_ENDPOINT", "localhost:9000"),
		MinIOAccessKey: getEnv("MINIO_ACCESS_KEY", "medfund"),
		MinIOSecretKey: getEnv("MINIO_SECRET_KEY", "medfund123"),
		MinIOBucket:    getEnv("MINIO_BUCKET", "medfund-invoices"),
		MinIOUseSSL:    getEnv("MINIO_USE_SSL", "false"),

		KafkaBrokers:         getEnv("KAFKA_BROKERS", "localhost:9092"),
		WkhtmltopdfBin:       getEnv("WKHTMLTOPDF_BIN", "wkhtmltopdf"),
		ContributionsBaseURL: getEnv("CONTRIBUTIONS_BASE_URL", "http://localhost:8084"),
	}
}

func getEnv(key, fallback string) string {
	if val, ok := os.LookupEnv(key); ok {
		return val
	}
	return fallback
}
