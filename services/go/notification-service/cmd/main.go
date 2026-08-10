package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/medfund/notification-service/internal/advice"
	"github.com/medfund/notification-service/internal/config"
	"github.com/medfund/notification-service/internal/events"
	"github.com/medfund/notification-service/internal/arrears"
	"github.com/medfund/notification-service/internal/handler"
	"github.com/medfund/notification-service/internal/invoice"
	"github.com/medfund/notification-service/internal/job"
	"github.com/medfund/notification-service/internal/lifecycle"
	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/notification"
	"github.com/medfund/notification-service/internal/receipt"
	"github.com/medfund/notification-service/internal/recipient"
	"github.com/medfund/notification-service/internal/retry"
	"github.com/medfund/notification-service/internal/storage"
	"github.com/medfund/notification-service/internal/template"
)

func main() {
	cfg := config.Load()

	app := fiber.New(fiber.Config{AppName: "MedFund Notification Service"})
	app.Use(recover.New())
	app.Use(logger.New())
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "notification-service"})
	})

	// Legacy HTTP routes (catch-all for the older ProcessEvent stub).
	svc := notification.NewService()
	h := handler.New(svc)
	h.RegisterRoutes(app)

	// ── Invoice email pipeline ──────────────────────────────────────
	// InvoicePdfReady → resolve recipient → fetch PDF → SMTP send →
	// NotificationSent. Each dependency fails open so a missing one
	// disables only the invoice pipeline.
	pool, err := pgxpool.New(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Printf("[notification] postgres unavailable: %v — recipient lookup disabled", err)
	}
	var resolver *recipient.Resolver
	if pool != nil {
		resolver = recipient.NewResolver(pool)
	}

	fetcher, err := storage.NewMinIOFetcher(cfg.MinIOEndpoint, cfg.MinIOAccessKey, cfg.MinIOSecretKey,
		cfg.MinIOUseSSL == "true")
	if err != nil {
		log.Printf("[notification] MinIO unavailable: %v — invoice attachments disabled", err)
	}

	sender := mail.Sender(mail.NewSMTPSender(cfg.SMTPHost, cfg.SMTPPort, cfg.SMTPUser, cfg.SMTPPassword))
	if cfg.SMTPHost == "" {
		log.Printf("[notification] SMTP_HOST empty — falling back to MockSender (logs only)")
		sender = &mail.MockSender{}
	}

	// Templates are tenant-overrideable via public.tenant_email_templates
	// (key INVOICE_ISSUED). Resolver gracefully degrades to the embedded
	// default when the row isn't present, so a tenant who hasn't
	// customised their template still gets a working email.
	templates := template.NewResolver(pool, invoice.DefaultSubject, invoice.DefaultHTMLBody())

	dispatcher, err := invoice.NewDispatcher(resolver, fetcher, sender, templates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("invoice dispatcher: %v", err)
	}

	publisher := events.NewPublisher(cfg.KafkaBrokers)
	defer publisher.Close()

	// Retry scheduler reuses the same Dispatcher for re-sends. Backoff
	// defaults to retry.DefaultBackoff ({30s, 2m, 5m, 15m} ≈ 22 min);
	// concurrency cap defends against a SMTP outage spawning thousands
	// of waiting goroutines when an entire billing run fails.
	retrySched := retry.New(nil, 50)

	// Job-completion email pipeline — separate dispatcher because the
	// recipient (a staff user) and the template (JOB_COMPLETED) are
	// distinct from the invoice path. Reuses the same SMTP sender,
	// template resolver, and retry scheduler.
	jobTemplates := template.NewResolver(pool, job.DefaultSubject, job.DefaultHTMLBody())
	jobDispatcher, err := job.NewDispatcher(
		job.DBStaffLookup{Pool: pool}, sender, jobTemplates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("job dispatcher: %v", err)
	}

	// Receipt pipeline — TransactionRecorded → email to group liaison
	// (via ForGroup) or member (via ForMember). No PDF; template body
	// carries the receipt lines.
	receiptTemplates := template.NewResolver(pool, receipt.DefaultSubject, receipt.DefaultHTMLBody())
	receiptDispatcher, err := receipt.NewDispatcher(resolver, fetcher, sender, receiptTemplates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("receipt dispatcher: %v", err)
	}

	// Arrears reminders + suspension/deactivation notices. Same recipient
	// resolver as invoice + receipt so a group notice lands on the liaison
	// (falling back to the group email) and a member notice hits the
	// member directly. No PDF — the template body carries balance +
	// days-overdue + next-step copy.
	arrearsTemplates := template.NewResolver(pool, arrears.DefaultSubject, arrears.DefaultHTMLBody())
	arrearsDispatcher, err := arrears.NewDispatcher(resolver, sender, arrearsTemplates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("arrears dispatcher: %v", err)
	}

	// Lifecycle pipeline: reacts to MEMBER_STATUS_CHANGED /
	// GROUP_STATUS_CHANGED. Handles the "your account has been
	// suspended / deactivated / reactivated" email for both
	// operator-triggered flips and arrears-triggered flips (the arrears
	// executor invokes user-service which then emits these same
	// events), so there's exactly one email per real transition.
	lifecycleTemplates := template.NewResolver(pool, lifecycle.DefaultSubject, lifecycle.DefaultHTMLBody())
	lifecycleDispatcher, err := lifecycle.NewDispatcher(resolver, sender, lifecycleTemplates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("lifecycle dispatcher: %v", err)
	}

	// Payment-advice pipeline: reacts to PAYMENT_ADVICE_GENERATED
	// (finance-service emits one per (run, payee) at execute-time).
	// Resolves the provider or member email via the same recipient
	// resolver used by the invoice / receipt pipelines, looks up the
	// ledger totals so the email body is a compact summary, and sends
	// via SMTP. Falls back to a header-only email when the DB lookup
	// fails so a slow secondary read doesn't stall delivery.
	adviceTemplates := template.NewResolver(pool, advice.DefaultSubject, advice.DefaultHTMLBody())
	var adviceLookup advice.AdviceLookup
	if pool != nil {
		adviceLookup = advice.NewDBLookup(pool)
	}
	adviceDispatcher, err := advice.NewDispatcher(resolver, sender, adviceTemplates, adviceLookup, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("advice dispatcher: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	if cfg.KafkaBrokers != "" && fetcher != nil {
		go runInvoiceConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID,
			dispatcher, publisher, retrySched)
	} else {
		log.Printf("[notification] invoice consumer disabled (kafka=%q minio=%v)",
			cfg.KafkaBrokers, fetcher != nil)
	}
	if cfg.KafkaBrokers != "" && pool != nil {
		go runJobConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID,
			jobDispatcher, retrySched)
	} else {
		log.Printf("[notification] job consumer disabled (kafka=%q postgres=%v)",
			cfg.KafkaBrokers, pool != nil)
	}
	if cfg.KafkaBrokers != "" && pool != nil {
		go runReceiptConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, receiptDispatcher, retrySched)
	} else {
		log.Printf("[notification] receipt consumer disabled (kafka=%q postgres=%v)",
			cfg.KafkaBrokers, pool != nil)
	}
	if cfg.KafkaBrokers != "" && pool != nil {
		go runArrearsConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, arrearsDispatcher, retrySched)
	} else {
		log.Printf("[notification] arrears consumer disabled (kafka=%q postgres=%v)",
			cfg.KafkaBrokers, pool != nil)
	}
	if cfg.KafkaBrokers != "" && pool != nil {
		go runMemberLifecycleConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, lifecycleDispatcher, retrySched)
		go runGroupLifecycleConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, lifecycleDispatcher, retrySched)
	} else {
		log.Printf("[notification] lifecycle consumers disabled (kafka=%q postgres=%v)",
			cfg.KafkaBrokers, pool != nil)
	}
	if cfg.KafkaBrokers != "" && pool != nil {
		go runAdviceConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, adviceDispatcher, publisher, retrySched)
	} else {
		log.Printf("[notification] advice consumer disabled (kafka=%q postgres=%v)",
			cfg.KafkaBrokers, pool != nil)
	}

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		cancel()
		_ = app.ShutdownWithTimeout(5 * time.Second)
	}()

	log.Printf("Notification Service starting on port %s", cfg.Port)
	log.Fatal(app.Listen(":" + cfg.Port))
}

// runInvoiceConsumer pulls InvoicePdfReady events, dispatches each,
// and publishes a NotificationSent with the actual outcome so audit /
// dashboards distinguish successful sends from quiet failures.
//
// On an initial failure, the event is handed off to retrySched for
// background re-dispatch with backoff. Re-dispatches do not publish
// their own per-attempt outcome — only the terminal result (SENT or
// DEAD_LETTERED) lands in the audit feed, so a worst-case failed
// invoice produces exactly two rows: the initial FAILED and a final
// DEAD_LETTERED. Per-attempt detail lives in notification-service logs.
func runInvoiceConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *invoice.Dispatcher, publisher *events.Publisher,
	retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-pdf-ready", groupID)
	sub.Run(ctx, func(payload []byte) {
		var e invoice.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed InvoicePdfReady: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		publisher.Publish(ctx, buildNotificationSent(e, res, "SENT", "FAILED"))

		if res.Ok {
			return
		}
		// Hand off to the retry scheduler. The retry chain re-runs
		// Dispatch silently; the onFinal callback publishes the
		// terminal outcome — SENT if a retry succeeded, DEAD_LETTERED
		// if every retry failed and we're giving up. No further
		// retries fire after the dead-letter, matching the explicit
		// "do not continue sending" requirement.
		scheduleRetry(ctx, retrySched, dispatcher, publisher, e)
	})
}

// scheduleRetry wires one event into retrySched and converts the
// terminal Outcome into the final NotificationSent on the audit feed.
func scheduleRetry(ctx context.Context, sched *retry.Scheduler,
	dispatcher *invoice.Dispatcher, publisher *events.Publisher, e invoice.Event) {

	var lastResult invoice.Result
	sched.RunAsync(ctx, e.InvoiceNumber,
		func(ctx context.Context, attempt int) error {
			r := dispatcher.Dispatch(ctx, e)
			lastResult = r
			if r.Ok {
				return nil
			}
			if r.Err != nil {
				return r.Err
			}
			// Dispatch can fail without a typed error (e.g. resolver
			// nil). Surface a synthetic one so the scheduler's retry
			// loop treats it as a failure rather than success.
			return errDispatchFailed
		},
		func(o retry.Outcome) {
			if o.Ok {
				publisher.Publish(ctx, buildNotificationSent(e, lastResult, "SENT", "SENT"))
				return
			}
			// Exhausted (or context-cancelled). Publish DEAD_LETTERED
			// with a synthetic error string that includes the attempt
			// count so the audit row is self-explanatory.
			cause := "unknown"
			if o.Err != nil {
				cause = o.Err.Error()
			}
			publisher.Publish(ctx, events.NotificationSent{
				InvoiceID:     e.InvoiceID,
				InvoiceNumber: e.InvoiceNumber,
				TenantID:      e.TenantID,
				Recipient:     lastResult.Recipient,
				Channel:       "EMAIL",
				Status:        "DEAD_LETTERED",
				Error:         fmt.Sprintf("exhausted %d retries: %s", o.Attempts, cause),
			})
		})
}

// buildNotificationSent translates a Dispatcher.Result into the wire
// shape. successStatus is the status applied when Result.Ok; failure
// applies when not — separate params so the initial-dispatch path
// can use SENT/FAILED while the retry success path stays SENT.
func buildNotificationSent(e invoice.Event, r invoice.Result,
	successStatus, failureStatus string) events.NotificationSent {

	sent := events.NotificationSent{
		InvoiceID:     e.InvoiceID,
		InvoiceNumber: e.InvoiceNumber,
		TenantID:      e.TenantID,
		Recipient:     r.Recipient,
		Channel:       "EMAIL",
		Status:        successStatus,
	}
	if !r.Ok {
		sent.Status = failureStatus
		if r.Err != nil {
			sent.Error = r.Err.Error()
		}
	}
	return sent
}

// Sentinel — only used when Dispatch returns Ok=false with a nil Err
// (a defensive belt-and-braces for a future Dispatch refactor).
var errDispatchFailed = fmt.Errorf("dispatch returned failure with no error")

// runJobConsumer reads JobCompleted events and dispatches them through
// the job-email pipeline. Skipped results (short jobs, scheduled
// triggers) emit nothing — they're filtered before any send happens.
// Errors hand off to the retry scheduler with backoff; after exhaustion
// the dead-letter is logged. Job emails are best-effort by design —
// the JobCompleted event itself is the durable record of completion,
// the email is just an out-of-band convenience.
func runJobConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *job.Dispatcher, retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.jobs.completed", groupID+"-jobs")
	sub.Run(ctx, func(payload []byte) {
		var e job.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed JobCompleted: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.Skipped || res.Ok {
			return
		}
		// Failed — schedule retries silently, log dead-letter on exhaustion.
		retrySched.RunAsync(ctx, "job:"+e.RunID,
			func(ctx context.Context, _ int) error {
				r := dispatcher.Dispatch(ctx, e)
				if r.Ok || r.Skipped {
					return nil
				}
				if r.Err != nil {
					return r.Err
				}
				return errDispatchFailed
			},
			func(o retry.Outcome) {
				if o.Ok {
					log.Printf("[notification] job email retry succeeded for run %s after %d attempts",
						e.RunID, o.Attempts)
					return
				}
				log.Printf("[notification] job email dead-lettered for run %s after %d attempts: %v",
					e.RunID, o.Attempts, o.Err)
			})
	})
}

// runReceiptConsumer reads TransactionRecorded events and dispatches a
// receipt email to the group liaison or the paying member. Same retry
// contract as the invoice consumer — best-effort with backoff, no
// per-attempt NotificationSent chatter (only the terminal outcome
// would land, but we omit it here since receipts are not yet tracked
// in the notification-sent audit stream).
func runReceiptConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *receipt.Dispatcher, retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.receipt-pdf-ready", groupID+"-receipts")
	sub.Run(ctx, func(payload []byte) {
		var e receipt.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed ReceiptPdfReady: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.Ok {
			return
		}
		retrySched.RunAsync(ctx, "receipt:"+e.TransactionID,
			func(ctx context.Context, _ int) error {
				r := dispatcher.Dispatch(ctx, e)
				if r.Ok {
					return nil
				}
				if r.Err != nil {
					return r.Err
				}
				return errDispatchFailed
			},
			func(o retry.Outcome) {
				if o.Ok {
					log.Printf("[notification] receipt retry succeeded for txn %s after %d attempts",
						e.TransactionNumber, o.Attempts)
					return
				}
				log.Printf("[notification] receipt dead-lettered for txn %s after %d attempts: %v",
					e.TransactionNumber, o.Attempts, o.Err)
			})
	})
}

// runArrearsConsumer reads ARREARS_NOTICE events (reminders +
// suspend/deactivate notices) and dispatches emails via the arrears
// pipeline. Same retry contract as invoice: transient failures go
// through the retry scheduler; terminal failures land in the log.
func runArrearsConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *arrears.Dispatcher, retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.arrears-notice", groupID+"-arrears")
	sub.Run(ctx, func(payload []byte) {
		var e arrears.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed ARREARS_NOTICE: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.Ok {
			return
		}
		retrySched.RunAsync(ctx, "arrears:"+e.Kind+":"+e.SubjectID,
			func(ctx context.Context, _ int) error {
				r := dispatcher.Dispatch(ctx, e)
				if r.Ok {
					return nil
				}
				if r.Err != nil {
					return r.Err
				}
				return errDispatchFailed
			},
			func(o retry.Outcome) {
				if o.Ok {
					log.Printf("[notification] arrears retry succeeded for %s %s after %d attempts",
						e.Kind, e.SubjectID, o.Attempts)
					return
				}
				log.Printf("[notification] arrears dead-lettered for %s %s after %d attempts: %v",
					e.Kind, e.SubjectID, o.Attempts, o.Err)
			})
	})
}

// memberLifecycleRaw / groupLifecycleRaw mirror the payloads
// user-service publishes. Kept separate here so a field rename on
// either side is a compile-time signal, not a silent decode-to-zero.
// The JSON key on the wire is `event`, not `eventType` — matches
// UserEventPublisher's payload envelope.
type memberLifecycleRaw struct {
	TenantID string `json:"tenantId"`
	MemberID string `json:"memberId"`
	Status   string `json:"status"`
	Reason   string `json:"reason"`
	Event    string `json:"event"`
}

type groupLifecycleRaw struct {
	TenantID string `json:"tenantId"`
	GroupID  string `json:"groupId"`
	Status   string `json:"status"`
	Reason   string `json:"reason"`
	Event    string `json:"event"`
}

// runMemberLifecycleConsumer reads MEMBER_STATUS_CHANGED events and
// emails the affected member. See lifecycle package comment for why
// this consumer + the group one collapse status-transition emails
// into a single path.
func runMemberLifecycleConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *lifecycle.Dispatcher, retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.users.member-lifecycle", groupID+"-lifecycle-member")
	sub.Run(ctx, func(payload []byte) {
		var raw memberLifecycleRaw
		if err := json.Unmarshal(payload, &raw); err != nil {
			log.Printf("[notification] drop malformed MEMBER_LIFECYCLE: %v", err)
			return
		}
		// Only status-change events feed this pipeline; enrolled /
		// terminated events still fire on the same topic for other
		// consumers.
		if raw.Event != "" && raw.Event != "MEMBER_STATUS_CHANGED" {
			return
		}
		e := lifecycle.Event{
			TenantID:    raw.TenantID,
			SubjectType: lifecycle.SubjectMember,
			SubjectID:   raw.MemberID,
			Status:      raw.Status,
			Reason:      raw.Reason,
		}
		dispatchLifecycle(ctx, dispatcher, retrySched, "member", e)
	})
}

// runGroupLifecycleConsumer — same shape, group side.
func runGroupLifecycleConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *lifecycle.Dispatcher, retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.users.group-lifecycle", groupID+"-lifecycle-group")
	sub.Run(ctx, func(payload []byte) {
		var raw groupLifecycleRaw
		if err := json.Unmarshal(payload, &raw); err != nil {
			log.Printf("[notification] drop malformed GROUP_LIFECYCLE: %v", err)
			return
		}
		if raw.Event != "" && raw.Event != "GROUP_STATUS_CHANGED" {
			return
		}
		e := lifecycle.Event{
			TenantID:    raw.TenantID,
			SubjectType: lifecycle.SubjectGroup,
			SubjectID:   raw.GroupID,
			Status:      raw.Status,
			Reason:      raw.Reason,
		}
		dispatchLifecycle(ctx, dispatcher, retrySched, "group", e)
	})
}

// dispatchLifecycle is the shared retry-wired body for both lifecycle
// consumers so the two runners stay two lines each. `label` shows up
// in the retry key + logs so operators can tell them apart.
func dispatchLifecycle(ctx context.Context, dispatcher *lifecycle.Dispatcher,
	retrySched *retry.Scheduler, label string, e lifecycle.Event) {
	res := dispatcher.Dispatch(ctx, e)
	if res.Ok {
		return
	}
	retrySched.RunAsync(ctx, "lifecycle:"+label+":"+e.SubjectID+":"+e.Status,
		func(ctx context.Context, _ int) error {
			r := dispatcher.Dispatch(ctx, e)
			if r.Ok {
				return nil
			}
			if r.Err != nil {
				return r.Err
			}
			return errDispatchFailed
		},
		func(o retry.Outcome) {
			if o.Ok {
				log.Printf("[notification] lifecycle %s retry succeeded for %s %s after %d attempts",
					label, e.SubjectID, e.Status, o.Attempts)
				return
			}
			log.Printf("[notification] lifecycle %s dead-lettered for %s %s after %d attempts: %v",
				label, e.SubjectID, e.Status, o.Attempts, o.Err)
		})
}

// runAdviceConsumer reads PAYMENT_ADVICE_GENERATED events and emails the
// provider or member payee a compact summary of the ledger totals for
// that run. Every dispatch outcome is fanned out to
// medfund.notifications.advice.sent so finance-service's
// PaymentAdviceStatusConsumer can flip PaymentAdviceRecord.status to
// 'sent' or 'failed' — closing the loop from generated → sent.
//
// Retry contract mirrors the arrears / lifecycle consumers: transient
// failures re-attempt via the retry scheduler; terminal failures
// dead-letter with an attempt count. On success, only the terminal
// SENT outcome is published (whether it took one attempt or five) —
// the intermediate FAILED that fed the retry loop still lands, so
// dashboards can distinguish first-try-clean from eventually-sent.
func runAdviceConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *advice.Dispatcher, publisher *events.Publisher,
	retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.payments.advice.generated", groupID+"-advice")
	sub.Run(ctx, func(payload []byte) {
		var e advice.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed PAYMENT_ADVICE_GENERATED: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		publisher.PublishAdviceOutcome(ctx, buildAdviceOutcome(e, res, "SENT", "FAILED", 1))
		if res.Ok {
			return
		}
		var lastResult advice.Result
		retrySched.RunAsync(ctx, "advice:"+e.AdviceID,
			func(ctx context.Context, _ int) error {
				r := dispatcher.Dispatch(ctx, e)
				lastResult = r
				if r.Ok {
					return nil
				}
				if r.Err != nil {
					return r.Err
				}
				return errDispatchFailed
			},
			func(o retry.Outcome) {
				if o.Ok {
					log.Printf("[notification] advice retry succeeded for %s after %d attempts",
						e.AdviceNumber, o.Attempts)
					publisher.PublishAdviceOutcome(ctx,
						buildAdviceOutcome(e, lastResult, "SENT", "SENT", o.Attempts))
					return
				}
				log.Printf("[notification] advice dead-lettered for %s after %d attempts: %v",
					e.AdviceNumber, o.Attempts, o.Err)
				cause := "unknown"
				if o.Err != nil {
					cause = o.Err.Error()
				}
				publisher.PublishAdviceOutcome(ctx, events.AdviceNotificationSent{
					AdviceID:     e.AdviceID,
					AdviceNumber: e.AdviceNumber,
					TenantID:     e.TenantID,
					Recipient:    lastResult.Recipient,
					Channel:      "EMAIL",
					Status:       "DEAD_LETTERED",
					Attempts:     o.Attempts,
					Error:        cause,
				})
			})
	})
}

func buildAdviceOutcome(e advice.Event, res advice.Result,
	okStatus, failStatus string, attempts int) events.AdviceNotificationSent {

	n := events.AdviceNotificationSent{
		AdviceID:     e.AdviceID,
		AdviceNumber: e.AdviceNumber,
		TenantID:     e.TenantID,
		Recipient:    res.Recipient,
		Channel:      "EMAIL",
		Attempts:     attempts,
	}
	if res.Ok {
		n.Status = okStatus
	} else {
		n.Status = failStatus
		if res.Err != nil {
			n.Error = res.Err.Error()
		}
	}
	return n
}
