package mail

import (
	"strings"
	"testing"
)

func TestBuildMIME_includesHTMLAndAttachment(t *testing.T) {
	body, boundary, err := buildMIME(Message{
		From:     "no-reply@medfund.healthcare",
		To:       "liaison@example.com",
		Subject:  "Invoice INV-001",
		HTMLBody: "<p>Hello Mary, attached is your invoice.</p>",
		Attachments: []Attachment{{
			Filename:    "invoice.pdf",
			ContentType: "application/pdf",
			Data:        []byte("%PDF-1.4 fake"),
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	s := body.String()
	for _, want := range []string{
		"--" + boundary,
		"Content-Type: text/html",
		"Hello Mary",
		"Content-Type: application/pdf",
		`filename="invoice.pdf"`,
		"--" + boundary + "--",
	} {
		if !strings.Contains(s, want) {
			t.Errorf("MIME body missing %q\n---\n%s\n---", want, s)
		}
	}
}

func TestMockSender_capturesEveryCall(t *testing.T) {
	m := &MockSender{}
	_ = m.Send(Message{To: "a@example.com", Subject: "1"})
	_ = m.Send(Message{To: "b@example.com", Subject: "2"})
	if len(m.Sent) != 2 {
		t.Fatalf("expected 2 captured messages, got %d", len(m.Sent))
	}
	if m.Sent[0].To != "a@example.com" || m.Sent[1].Subject != "2" {
		t.Errorf("captured messages wrong: %+v", m.Sent)
	}
}
