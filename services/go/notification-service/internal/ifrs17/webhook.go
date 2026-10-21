package ifrs17

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// WebhookSender POSTs the raw event JSON to a configured URL. Kept
// as an interface so tests can inject a recording double without
// spinning up an httptest server for every case.
type WebhookSender interface {
	Send(ctx context.Context, url string, event Event) error
}

// HTTPWebhookSender is the production implementation. Serialises the
// event as JSON and POSTs it with a short timeout so a slow
// downstream never stalls the consumer loop.
type HTTPWebhookSender struct {
	Client *http.Client
}

func NewHTTPWebhookSender() *HTTPWebhookSender {
	return &HTTPWebhookSender{Client: &http.Client{Timeout: 5 * time.Second}}
}

func (s *HTTPWebhookSender) Send(ctx context.Context, url string, event Event) error {
	body, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("marshal event: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Medfund-Event", "IFRS17_MATERIAL_EVENT")

	resp, err := s.Client.Do(req)
	if err != nil {
		return fmt.Errorf("post webhook: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("webhook returned %d: %s", resp.StatusCode, string(snippet))
	}
	return nil
}
