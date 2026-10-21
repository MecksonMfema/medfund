package config

import "os"

type Config struct {
	Port            string
	KafkaBrokers    string
	ConsumerGroupID string

	// SMTP defaults target mailpit on the dev compose stack
	// (localhost:1026, no auth). For prod, set SMTP_HOST/PORT/USER/PASSWORD
	// via env to your SES endpoint.
	SMTPHost     string
	SMTPPort     string
	SMTPUser     string
	SMTPPassword string
	SMTPFrom     string

	SMSProvider  string
	SMSAPIKey    string
	FCMServerKey string

	// Postgres used to resolve recipient details from the tenant schema
	// (group_liaisons for groups, members for individuals). Empty value
	// disables recipient lookup and the consumer falls back to logging
	// only — useful in CI environments without a database.
	DatabaseURL string

	// MinIO — used to fetch the rendered invoice PDF the file-service
	// uploaded. Same credentials as file-service so both can talk to
	// the local minio container in dev.
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinIOUseSSL    string

	// IFRS 17 dispatcher — the Redis URL backs the throttle SetNX,
	// and TenancyServiceURL is where the dispatcher fetches active
	// notification-config rows. Empty values disable the ifrs17
	// pipeline (matching the "one channel down doesn't crash the
	// service" pattern of the other consumers).
	RedisURL          string
	TenancyServiceURL string

	// Phase 17 scheduled-report delivery. GatewayBaseURL is the
	// public host the signed download link points at (the URL a
	// recipient clicks in an email). WebBaseURL is the Angular host
	// used for the /public/unsubscribe/:token confirm page. The
	// download-token secret is the shared HMAC key — must match
	// finance-service SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET.
	ReportPayloadsBucket        string
	ScheduledReportDownloadURL  string
	ScheduledUnsubscribeWebURL  string
	ScheduledDownloadSecret     string
}

func Load() *Config {
	return &Config{
		Port:            getEnv("PORT", "3001"),
		KafkaBrokers:    getEnv("KAFKA_BROKERS", "localhost:9092"),
		ConsumerGroupID: getEnv("CONSUMER_GROUP_ID", "notification-service"),

		SMTPHost:     getEnv("SMTP_HOST", "localhost"),
		SMTPPort:     getEnv("SMTP_PORT", "1026"),
		SMTPUser:     getEnv("SMTP_USER", ""),
		SMTPPassword: getEnv("SMTP_PASSWORD", ""),
		SMTPFrom:     getEnv("SMTP_FROM", "no-reply@medfund.healthcare"),

		SMSProvider:  getEnv("SMS_PROVIDER", "mock"),
		SMSAPIKey:    getEnv("SMS_API_KEY", ""),
		FCMServerKey: getEnv("FCM_SERVER_KEY", ""),

		DatabaseURL: getEnv("DATABASE_URL",
			"postgres://medfund:medfund@localhost:5433/medfund?sslmode=disable"),

		MinIOEndpoint:  getEnv("MINIO_ENDPOINT", "localhost:9000"),
		MinIOAccessKey: getEnv("MINIO_ACCESS_KEY", "medfund"),
		MinIOSecretKey: getEnv("MINIO_SECRET_KEY", "medfund123"),
		MinIOUseSSL:    getEnv("MINIO_USE_SSL", "false"),

		RedisURL:          getEnv("REDIS_URL", "redis://localhost:6379/0"),
		TenancyServiceURL: getEnv("TENANCY_SERVICE_URL", "http://localhost:8081"),

		ReportPayloadsBucket:       getEnv("REPORT_PAYLOADS_BUCKET", "medfund-report-payloads"),
		ScheduledReportDownloadURL: getEnv("SCHEDULED_REPORT_GATEWAY_URL", "http://localhost:3000"),
		ScheduledUnsubscribeWebURL: getEnv("SCHEDULED_REPORT_WEB_URL", "http://localhost:5100"),
		ScheduledDownloadSecret:    getEnv("SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET", ""),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
