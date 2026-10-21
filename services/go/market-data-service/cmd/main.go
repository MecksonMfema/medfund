package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/robfig/cron/v3"

	"github.com/medfund/shared/httpserver"

	"github.com/medfund/market-data-service/internal/adapters"
	"github.com/medfund/market-data-service/internal/config"
	"github.com/medfund/market-data-service/internal/publisher"
	"github.com/medfund/market-data-service/internal/scheduler"
	"github.com/medfund/market-data-service/internal/tenancy"
	"github.com/medfund/shared/flags"
)

// main wires the daemon: cron scheduler pulling per-tenant enabled
// configs, dispatching to per-jurisdiction adapters, publishing curves
// to Kafka. Also exposes a small Fiber app so kube liveness + a manual
// /trigger endpoint work per the plan's manual verification steps.
func main() {
	cfg := config.Load()

	app := httpserver.New(httpserver.Options{AppName: "MedFund Market Data Service"})
	app.Use(recover.New())
	app.Use(logger.New())
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "market-data-service"})
	})

	pub := publisher.New(cfg.KafkaBrokers)
	defer pub.Close()

	var configs tenancy.ConfigLookup
	if cfg.TenancyServiceURL != "" {
		configs = tenancy.NewHTTPClient(cfg.TenancyServiceURL)
	}
	sched := scheduler.New(configs, pub)

	// Manual trigger — /trigger runs Tick() out-of-band. Useful for
	// dev + the plan's manual verification step.
	app.Post("/trigger", func(c *fiber.Ctx) error {
		published, failed := sched.Tick(c.UserContext())
		return c.JSON(fiber.Map{
			"published": published,
			"failed":    failed,
		})
	})

	// Manual per-currency test — /trigger/{source}/{currency} runs
	// one adapter fetch without publishing. Handy for smoke-testing
	// a jurisdiction feed change.
	app.Get("/trigger/:source/:currency", func(c *fiber.Ctx) error {
		adapter := adapters.For(c.Params("source"))
		if adapter == nil {
			return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "no adapter"})
		}
		ctx, cancel := context.WithTimeout(c.UserContext(), 10*time.Second)
		defer cancel()
		curve, err := adapter.Fetch(ctx, c.Params("currency"))
		if err != nil {
			return c.Status(fiber.StatusBadGateway).JSON(fiber.Map{"error": err.Error()})
		}
		return c.JSON(curve)
	})

	ctx, cancel := context.WithCancel(context.Background())

	// ── Platform feature flags ──────────────────────────────────────────────
	// Seed from tenancy-service, then follow platform.feature-flags.v1 for
	// live toggles. Fails open: an unreachable tenancy-service leaves every
	// flag disabled rather than blocking startup. Nothing reads a flag in
	// this service yet — this is the plumbing (plan Phase 3).
	flagRegistry := flags.New(cfg.TenancyServiceURL)
	if err := flagRegistry.Bootstrap(ctx); err != nil {
		log.Printf("[market-data-service] feature-flag bootstrap failed: %v", err)
	}
	go flagRegistry.StartConsumer(ctx, cfg.KafkaBrokers, "market-data-service")

	var cronRunner *cron.Cron
	if configs != nil {
		cronRunner = cron.New(cron.WithLocation(time.UTC))
		if _, err := cronRunner.AddFunc(cfg.FetchCron, func() {
			tickCtx, tickCancel := context.WithTimeout(ctx, 5*time.Minute)
			defer tickCancel()
			sched.Tick(tickCtx)
		}); err != nil {
			log.Fatalf("[market-data] cron schedule invalid %q: %v", cfg.FetchCron, err)
		}
		cronRunner.Start()
		log.Printf("[market-data] scheduler active with cron=%q", cfg.FetchCron)
	} else {
		log.Printf("[market-data] scheduler disabled — TENANCY_SERVICE_URL not set")
	}

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		if cronRunner != nil {
			ctxStop := cronRunner.Stop()
			<-ctxStop.Done()
		}
		cancel()
		_ = app.ShutdownWithTimeout(5 * time.Second)
	}()

	log.Printf("Market Data Service starting on port %s", cfg.Port)
	log.Fatal(app.Listen(":" + cfg.Port))
}
