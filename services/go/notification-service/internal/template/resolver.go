// Package template resolves per-tenant email templates from
// public.tenant_email_templates, falling back to an embedded default
// when a tenant hasn't customised the template for a given key.
package template

import (
	"context"
	"errors"
	"log"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Template is the rendered shape — both fields are still Go-template
// strings the caller will Execute against per-event variables.
type Template struct {
	Subject  string
	HTMLBody string
	// Source is "tenant" when a tenant_email_templates row served it,
	// "default" when the embedded fallback did. Surfaced in logs so a
	// support operator can tell at a glance which path served a given
	// dispatch.
	Source string
}

// Resolver looks up enabled templates by (tenantId, key). nil pool is
// allowed — every lookup returns the default in that case, useful in
// tests and in dev environments without Postgres.
type Resolver struct {
	pool             *pgxpool.Pool
	defaultSubject   string
	defaultHTMLBody  string
}

func NewResolver(pool *pgxpool.Pool, defaultSubject, defaultHTMLBody string) *Resolver {
	return &Resolver{
		pool:            pool,
		defaultSubject:  defaultSubject,
		defaultHTMLBody: defaultHTMLBody,
	}
}

// Resolve returns the tenant's customised template for the key when
// one exists and is enabled, otherwise the embedded default. Never
// errors — a database failure is logged and treated as "no override",
// so a flaky template lookup never blocks email delivery.
func (r *Resolver) Resolve(ctx context.Context, tenantID, key string) Template {
	if r.pool == nil || tenantID == "" {
		return r.fallback()
	}
	var subject, htmlBody string
	err := r.pool.QueryRow(ctx, `
		SELECT subject, html_body
		  FROM public.tenant_email_templates
		 WHERE tenant_id = $1 AND template_key = $2 AND enabled = TRUE
		 LIMIT 1`, tenantID, key).Scan(&subject, &htmlBody)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		return r.fallback()
	case err != nil:
		log.Printf("[template] lookup %s/%s failed (%v) — falling back to default", tenantID, key, err)
		return r.fallback()
	}
	return Template{Subject: subject, HTMLBody: htmlBody, Source: "tenant"}
}

func (r *Resolver) fallback() Template {
	return Template{
		Subject:  r.defaultSubject,
		HTMLBody: r.defaultHTMLBody,
		Source:   "default",
	}
}
