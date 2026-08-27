package config

import (
	"os"
	"testing"
)

func TestLoad_Defaults(t *testing.T) {
	cfg := Load()
	if cfg.Port != "3000" {
		t.Fatalf("expected port 3000, got %s", cfg.Port)
	}
	if cfg.RateLimitPerMin != 120 {
		t.Fatalf("expected rate limit 120, got %d", cfg.RateLimitPerMin)
	}
	if cfg.TenancyServiceURL != "http://localhost:8081" {
		t.Fatalf("expected tenancy URL http://localhost:8081, got %s", cfg.TenancyServiceURL)
	}
}

func TestLoad_FromEnv(t *testing.T) {
	os.Setenv("PORT", "9000")
	defer os.Unsetenv("PORT")

	cfg := Load()
	if cfg.Port != "9000" {
		t.Fatalf("expected port 9000, got %s", cfg.Port)
	}
}

// Phase 14 §Actuarial Phase 6 — ai-service pass-through wires the tenant-admin
// actuarial-bases dropdowns to the YAML catalogue in ai-service. The default
// mirrors the docker-compose ai-service port; env override lets the deploy
// point at any URL.
func TestLoad_AiServiceURL_Default(t *testing.T) {
	cfg := Load()
	if cfg.AiServiceURL != "http://localhost:8000" {
		t.Fatalf("expected ai service default http://localhost:8000, got %s", cfg.AiServiceURL)
	}
}

func TestLoad_AiServiceURL_FromEnv(t *testing.T) {
	os.Setenv("AI_SERVICE_URL", "http://ai:9999")
	defer os.Unsetenv("AI_SERVICE_URL")

	cfg := Load()
	if cfg.AiServiceURL != "http://ai:9999" {
		t.Fatalf("expected ai service http://ai:9999, got %s", cfg.AiServiceURL)
	}
}
