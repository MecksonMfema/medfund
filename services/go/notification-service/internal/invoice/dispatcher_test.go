package invoice

import (
	"context"
	"strings"
	"testing"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/recipient"
)

// stubFetcher returns canned PDF bytes for any key.
type stubFetcher struct {
	data []byte
	err  error
}

func (s stubFetcher) GetObject(_ context.Context, _, _ string) ([]byte, error) {
	return s.data, s.err
}

func TestRenderBody_substitutesAllFields(t *testing.T) {
	d, err := NewDispatcher(nil, nil, nil, "no-reply@medfund.healthcare")
	if err != nil {
		t.Fatal(err)
	}
	body, err := d.renderBody(
		recipient.Recipient{Email: "mary@example.com", DisplayName: "Mary Jones", Kind: "GROUP_LIAISON"},
		Event{
			InvoiceNumber: "INV-001",
			CurrencyCode:  "USD",
			TotalAmount:   "150.00",
			PeriodStart:   "2026-06-01",
			PeriodEnd:     "2026-06-30",
			DueDate:       "2026-07-30",
		})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Mary Jones", "INV-001", "USD", "150.00", "2026-07-30"} {
		if !strings.Contains(body, want) {
			t.Errorf("rendered body missing %q\n---\n%s\n---", want, body)
		}
	}
}

func TestDispatch_dropsEventWithoutRecipientLookup(t *testing.T) {
	sender := &mail.MockSender{}
	d, _ := NewDispatcher(nil, stubFetcher{}, sender, "from@example.com")

	d.Dispatch(context.Background(), Event{InvoiceNumber: "INV-002", GroupID: "g-1"})

	if len(sender.Sent) != 0 {
		t.Errorf("expected zero sends when resolver is nil, got %d", len(sender.Sent))
	}
}
