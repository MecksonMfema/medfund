package ifrs17

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/medfund/notification-service/internal/mail"
)

// Result mirrors the shape used by the invoice / receipt / lifecycle
// pipelines so main.go can wire everything into the same retry
// scheduler if needed. `Attempts` here counts recipients fanned out
// on this dispatch, not retry attempts — useful for logs.
type Result struct {
	Ok           bool
	Attempts     int
	Delivered    int
	Throttled    int
	FailedEmails int
	FailedHooks  int
	Err          error
}

// Dispatcher wires the per-tenant lookup + throttle + delivery
// backends. Every field is safe to leave nil — a nil field turns
// that branch of the pipeline into a no-op and the dispatcher logs
// what it skipped. This matches the "fail-open" contract the rest
// of notification-service uses so a missing Redis or SMTP box
// doesn't crash the process, just disables one channel.
type Dispatcher struct {
	Configs ConfigLookup
	Sender  mail.Sender
	Webhook WebhookSender
	Throt   Throttle
	From    string
}

func NewDispatcher(configs ConfigLookup, sender mail.Sender,
	webhook WebhookSender, throt Throttle, from string) *Dispatcher {
	return &Dispatcher{
		Configs: configs,
		Sender:  sender,
		Webhook: webhook,
		Throt:   throt,
		From:    from,
	}
}

// Dispatch is the end-to-end pipeline for one material event.
// Every failure inside the per-config loop is logged and counted but
// does not abort the loop — one bad recipient must not silence the
// others. Returns Err only when the initial config fetch itself
// fails, which is the only case worth retrying at the consumer
// level.
func (d *Dispatcher) Dispatch(ctx context.Context, event Event) Result {
	if d == nil || d.Configs == nil {
		return Result{Err: fmt.Errorf("dispatcher: config lookup not configured")}
	}
	if event.TenantID == "" || event.EventType == "" {
		// A malformed event is a no-op so the consumer commits the
		// offset and moves on — retrying a shape error is pointless.
		log.Printf("[ifrs17] drop malformed event: tenantId=%q eventType=%q",
			event.TenantID, event.EventType)
		return Result{Ok: true}
	}

	configs, err := d.Configs.ActiveFor(ctx, event.TenantID, event.EventType)
	if err != nil {
		log.Printf("[ifrs17] fetch configs failed tenant=%s type=%s: %v",
			event.TenantID, event.EventType, err)
		return Result{Err: fmt.Errorf("fetch configs: %w", err)}
	}
	if len(configs) == 0 {
		// Silent no-op — a tenant with nobody subscribed is fine.
		return Result{Ok: true}
	}

	body, err := renderBody(event)
	if err != nil {
		// Template render failure is a bug in this service, not a
		// per-recipient issue — bail out for the whole event.
		log.Printf("[ifrs17] render template failed for %s: %v",
			event.EventType, err)
		return Result{Err: fmt.Errorf("render body: %w", err)}
	}
	subject := subjectFor(event.EventType)

	res := Result{Attempts: len(configs)}
	for _, cfg := range configs {
		if !cfg.IsActive {
			continue
		}
		window := time.Duration(cfg.ThrottleMinutes) * time.Minute
		key := throttleKey(cfg.TenantID, event.EventType, cfg.Recipient, cfg.ThrottleMinutes)
		if d.Throt != nil {
			ok, err := d.Throt.Acquire(ctx, key, window)
			if err != nil {
				log.Printf("[ifrs17] throttle backend error tenant=%s type=%s: %v — delivering anyway",
					event.TenantID, event.EventType, err)
				// Fail-open — a downed Redis must not silence
				// operator emails during an incident.
			} else if !ok {
				res.Throttled++
				log.Printf("[ifrs17] throttled tenant=%s type=%s recipient=%s window=%s",
					event.TenantID, event.EventType, cfg.Recipient, window)
				continue
			}
		}

		if wantsEmail(cfg.DeliveryMethod) {
			if err := d.deliverEmail(cfg.Recipient, subject, body); err != nil {
				res.FailedEmails++
				log.Printf("[ifrs17] email delivery failed tenant=%s type=%s recipient=%s: %v",
					event.TenantID, event.EventType, cfg.Recipient, err)
			} else {
				res.Delivered++
			}
		}
		if wantsWebhook(cfg.DeliveryMethod) {
			if err := d.deliverWebhook(ctx, cfg.Recipient, event); err != nil {
				res.FailedHooks++
				log.Printf("[ifrs17] webhook delivery failed tenant=%s type=%s recipient=%s: %v",
					event.TenantID, event.EventType, cfg.Recipient, err)
			} else {
				res.Delivered++
			}
		}
	}

	res.Ok = true
	return res
}

func (d *Dispatcher) deliverEmail(recipient, subject, body string) error {
	if d.Sender == nil {
		return fmt.Errorf("email sender not configured")
	}
	return d.Sender.Send(mail.Message{
		From:     d.From,
		To:       recipient,
		Subject:  subject,
		HTMLBody: body,
	})
}

func (d *Dispatcher) deliverWebhook(ctx context.Context, url string, event Event) error {
	if d.Webhook == nil {
		return fmt.Errorf("webhook sender not configured")
	}
	return d.Webhook.Send(ctx, url, event)
}

func wantsEmail(m string) bool  { return m == "EMAIL" || m == "BOTH" }
func wantsWebhook(m string) bool { return m == "WEBHOOK" || m == "BOTH" }
