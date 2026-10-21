package config

import "os"

// Config gathers env-driven wiring for the market-data-service. Every
// field has a sensible dev default so `make market-data-service` runs
// against the local Docker infra stack without an env file.
type Config struct {
	Port         string
	KafkaBrokers string

	// The tenancy-service address the daemon polls each fetch tick for
	// the enabled (tenant, currency, source) tuples. Empty disables
	// the scheduler — the service still boots (health endpoint stays
	// up) but no fetches fire.
	TenancyServiceURL string

	// Cron expression driving the fetch cadence. Defaults to "daily
	// at 06:00 UTC" per the plan; tightened via env for load tests
	// or per-tenant on-demand triggers.
	FetchCron string
}

// Load returns a Config populated from env with the dev defaults.
func Load() *Config {
	return &Config{
		Port:              getEnv("PORT", "3005"),
		KafkaBrokers:      getEnv("KAFKA_BROKERS", "localhost:9092"),
		TenancyServiceURL: getEnv("TENANCY_SERVICE_URL", "http://localhost:8081"),
		FetchCron:         getEnv("FETCH_CRON", "0 6 * * *"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
