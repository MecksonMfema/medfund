package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/medfund/file-service/internal/contributions"
)

func TestFetchRenderPayloadWithRetry_recoversOnSecondAttempt(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls < 2 {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"invoice":{"id":"i-1","invoiceNumber":"CS-1"},"statement":{"header":{}},"contributions":[]}`))
	}))
	defer srv.Close()

	client := contributions.New(srv.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	rd, err := fetchRenderPayloadWithRetry(ctx, client, "t-1", "i-1", "CS-1")
	if err != nil {
		t.Fatalf("expected recovery, got err: %v", err)
	}
	if rd == nil || rd.Invoice.InvoiceNumber != "CS-1" {
		t.Fatalf("expected populated payload, got %+v", rd)
	}
	if calls != 2 {
		t.Errorf("expected 2 upstream calls (fail, succeed), got %d", calls)
	}
}

func TestFetchRenderPayloadWithRetry_exhaustsAndReturnsLastError(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		http.Error(w, "persistent", http.StatusInternalServerError)
	}))
	defer srv.Close()

	client := contributions.New(srv.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := fetchRenderPayloadWithRetry(ctx, client, "t-1", "i-1", "CS-1")
	if err == nil {
		t.Fatal("expected error after exhausted retries")
	}
	if calls != 3 {
		t.Errorf("expected 3 attempts, got %d", calls)
	}
	// Terminal error must carry the last upstream cause so the operator
	// log line shows what actually broke - a plain "exhausted" message
	// would strip the diagnostic detail.
	if !strings.Contains(err.Error(), "persistent") && !strings.Contains(err.Error(), "500") {
		t.Errorf("terminal error should carry the last cause, got: %v", err)
	}
}
