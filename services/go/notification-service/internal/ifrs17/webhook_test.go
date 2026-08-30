package ifrs17

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHTTPWebhookSender_Send_postsJSONBody(t *testing.T) {
	var got Event
	var gotHeader string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHeader = r.Header.Get("X-Medfund-Event")
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &got)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	s := NewHTTPWebhookSender()
	err := s.Send(context.Background(), srv.URL, Event{
		TenantID: "tnt-1", EventType: EventTypeCsmNegative,
		Message: "csm negative",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotHeader != "IFRS17_MATERIAL_EVENT" {
		t.Errorf("expected event header, got %q", gotHeader)
	}
	if got.TenantID != "tnt-1" || got.EventType != EventTypeCsmNegative {
		t.Errorf("payload not decoded correctly: %+v", got)
	}
}

func TestHTTPWebhookSender_Send_nonOkStatusReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "you are not on the guest list", http.StatusForbidden)
	}))
	defer srv.Close()

	s := NewHTTPWebhookSender()
	err := s.Send(context.Background(), srv.URL, Event{TenantID: "tnt-1", EventType: "X"})
	if err == nil {
		t.Fatal("expected error on 403")
	}
	if !strings.Contains(err.Error(), "403") {
		t.Errorf("expected 403 in error, got %v", err)
	}
}
