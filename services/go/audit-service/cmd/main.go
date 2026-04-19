package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/audit-service/internal/audit"
	"github.com/medfund/audit-service/internal/cache"
	"github.com/medfund/audit-service/internal/config"
	"github.com/medfund/audit-service/internal/consumer"
	"github.com/medfund/audit-service/internal/db"
	"github.com/medfund/audit-service/internal/handler"
)

func main() {
	cfg := config.Load()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// ── PostgreSQL ────────────────────────────────────────────────────────────
	pool, err := db.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("[audit-service] database connection failed: %v", err)
	}
	defer pool.Close()
	log.Printf("[audit-service] connected to database")

	if err := db.Migrate(ctx, pool); err != nil {
		log.Fatalf("[audit-service] schema migration failed: %v", err)
	}
	log.Printf("[audit-service] schema up to date")

	store := audit.NewStore(pool)

	// ── Redis cache (optional) ─────────────────────────────────────────────
	var redisCache *cache.Cache
	if c, err := cache.New(cfg.RedisURL); err != nil {
		log.Printf("[audit-service] Redis unavailable, caching disabled: %v", err)
	} else {
		redisCache = c
		defer redisCache.Close()
		// Invalidate query cache whenever a new event is written to the DB
		store.SetOnAppend(func() {
			redisCache.Invalidate(context.Background())
		})
	}

	// ── Kafka consumer ─────────────────────────────────────────────────────
	c := consumer.New(cfg.KafkaBrokers, store)
	c.Start(ctx)

	// ── HTTP server ────────────────────────────────────────────────────────
	app := fiber.New(fiber.Config{AppName: "MedFund Audit Service"})
	app.Use(recover.New())
	app.Use(logger.New())

	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "audit-service"})
	})

	h := handler.New(store, redisCache)
	h.RegisterRoutes(app)

	// ── Graceful shutdown ─────────────────────────────────────────────────
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-quit
		log.Println("[audit-service] shutting down...")
		cancel()
		_ = app.Shutdown()
	}()

	log.Printf("[audit-service] listening on :%s  kafka=%s  db=%s",
		cfg.Port, cfg.KafkaBrokers, cfg.DatabaseURL)
	if err := app.Listen(":" + cfg.Port); err != nil {
		log.Fatalf("[audit-service] server error: %v", err)
	}
}
