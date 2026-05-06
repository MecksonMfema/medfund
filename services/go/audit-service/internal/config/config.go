package config

import "os"

type Config struct {
	Port            string
	KafkaBrokers    string
	ConsumerGroupID string
	DatabaseURL     string
	RedisURL        string
}

func Load() *Config {
	return &Config{
		Port:            getEnv("PORT", "3002"),
		KafkaBrokers:    getEnv("KAFKA_BROKERS", "localhost:9092"),
		ConsumerGroupID: getEnv("CONSUMER_GROUP_ID", "audit-service"),
		DatabaseURL:     getEnv("DATABASE_URL", "postgres://medfund:medfund@localhost:5433/medfund?sslmode=disable"),
		RedisURL:        getEnv("REDIS_URL", "redis://localhost:6380/0"),
	}
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
