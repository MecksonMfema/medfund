package config

import "os"

// Config holds all gateway configuration values loaded from environment variables.
type Config struct {
	Port                 string
	KeycloakURL          string
	KeycloakRealm        string
	KeycloakAdminUser    string
	KeycloakAdminPass    string
	TenancyServiceURL string
	UserServiceURL    string
	ClaimsServiceURL  string
	ContribServiceURL string
	FinanceServiceURL string
	RulesServiceURL   string
	NotifServiceURL   string
	AuditServiceURL   string
	FileServiceURL    string
	PaymentServiceURL string
	KafkaBrokers      string
	RateLimitPerMin   int
	/**
	 * Comma-separated list of CORS-allowed origins for the browser-facing
	 * web app. Defaults to the Angular dev server on :5100 and the previous
	 * :4200 (so a half-migrated environment doesn't break overnight). Set
	 * WEB_ORIGINS at deploy time to lock the production allowlist down.
	 */
	WebOrigins string
}

// Load reads configuration from environment variables with sensible defaults.
func Load() *Config {
	return &Config{
		Port:              getEnv("PORT", "3000"),
		KeycloakURL:       getEnv("KEYCLOAK_URL", "http://localhost:9080"),
		KeycloakRealm:     getEnv("KEYCLOAK_REALM", "medfund-platform"),
		TenancyServiceURL: getEnv("TENANCY_SERVICE_URL", "http://localhost:8081"),
		UserServiceURL:    getEnv("USER_SERVICE_URL", "http://localhost:8082"),
		ClaimsServiceURL:  getEnv("CLAIMS_SERVICE_URL", "http://localhost:8083"),
		ContribServiceURL: getEnv("CONTRIBUTIONS_SERVICE_URL", "http://localhost:8084"),
		FinanceServiceURL: getEnv("FINANCE_SERVICE_URL", "http://localhost:8085"),
		RulesServiceURL:   getEnv("RULES_SERVICE_URL", "http://localhost:8086"),
		NotifServiceURL:   getEnv("NOTIFICATION_SERVICE_URL", "http://localhost:3001"),
		AuditServiceURL:   getEnv("AUDIT_SERVICE_URL", "http://localhost:3002"),
		FileServiceURL:    getEnv("FILE_SERVICE_URL", "http://localhost:3003"),
		PaymentServiceURL: getEnv("PAYMENT_SERVICE_URL", "http://localhost:3004"),
		KafkaBrokers:      getEnv("KAFKA_BROKERS", "localhost:9092"),
		RateLimitPerMin:   120,
		WebOrigins:        getEnv("WEB_ORIGINS", "http://localhost:5100,http://localhost:4200"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
