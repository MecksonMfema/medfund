package template

import (
	"context"
	"testing"
)

// Nil-pool path is the most important branch to lock down: a
// notification-service that boots without a database must still send
// emails using the embedded default rather than crashing on every
// dispatch.
func TestResolve_nilPool_returnsDefault(t *testing.T) {
	r := NewResolver(nil, "subj-{{.X}}", "<p>body-{{.X}}</p>")
	tmpl := r.Resolve(context.Background(), "any-tenant", "INVOICE_ISSUED")

	if tmpl.Source != "default" {
		t.Errorf("Source = %q, want %q", tmpl.Source, "default")
	}
	if tmpl.Subject != "subj-{{.X}}" || tmpl.HTMLBody != "<p>body-{{.X}}</p>" {
		t.Errorf("default not returned verbatim: %+v", tmpl)
	}
}

func TestResolve_emptyTenantID_returnsDefault(t *testing.T) {
	r := NewResolver(nil, "s", "b")
	tmpl := r.Resolve(context.Background(), "", "INVOICE_ISSUED")
	if tmpl.Source != "default" {
		t.Errorf("empty tenantID should fall back to default, got source=%q", tmpl.Source)
	}
}
