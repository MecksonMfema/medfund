package mail

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"log"
	"mime/quotedprintable"
	"net/smtp"
	"strings"
)

// Attachment is a single MIME part attached to an outgoing message.
type Attachment struct {
	Filename    string
	ContentType string
	Data        []byte
}

// Message captures what Sender.Send turns into wire bytes — kept as a
// plain struct so tests can assert against it without simulating SMTP.
type Message struct {
	From        string
	To          string
	Subject     string
	HTMLBody    string
	Attachments []Attachment
}

// Sender abstracts the SMTP backend so the rest of notification-service
// can inject mocks in tests and swap in SES later behind the same
// interface.
type Sender interface {
	Send(m Message) error
}

// SMTPSender connects to an SMTP server. No auth when SMTPUser is
// empty — that's the path through mailpit in local dev. Auth is
// PLAIN when SMTPUser is set, matching what SES wants.
type SMTPSender struct {
	Host string
	Port string
	User string
	Pass string
}

func NewSMTPSender(host, port, user, pass string) *SMTPSender {
	return &SMTPSender{Host: host, Port: port, User: user, Pass: pass}
}

func (s *SMTPSender) Send(m Message) error {
	body, boundary, err := buildMIME(m)
	if err != nil {
		return err
	}
	addr := s.Host + ":" + s.Port
	var auth smtp.Auth
	if s.User != "" {
		auth = smtp.PlainAuth("", s.User, s.Pass, s.Host)
	}
	headers := []byte(fmt.Sprintf("From: %s\r\nTo: %s\r\nSubject: %s\r\nMIME-Version: 1.0\r\n"+
		"Content-Type: multipart/mixed; boundary=%s\r\n\r\n",
		m.From, m.To, m.Subject, boundary))
	if err := smtp.SendMail(addr, auth, m.From, []string{m.To},
		append(headers, body.Bytes()...)); err != nil {
		return fmt.Errorf("smtp send: %w", err)
	}
	log.Printf("[mail] sent to=%s subject=%q attachments=%d", m.To, m.Subject, len(m.Attachments))
	return nil
}

// buildMIME returns the message body (without the top-level headers,
// which Send writes itself) and the multipart boundary. Pulled out
// for unit testing — assertions can inspect the exact bytes the
// real Send hands to smtp.SendMail.
func buildMIME(m Message) (bytes.Buffer, string, error) {
	boundary := randomBoundary()
	var buf bytes.Buffer

	// HTML part
	fmt.Fprintf(&buf, "--%s\r\nContent-Type: text/html; charset=utf-8\r\n"+
		"Content-Transfer-Encoding: quoted-printable\r\n\r\n", boundary)
	qp := quotedprintable.NewWriter(&buf)
	if _, err := qp.Write([]byte(m.HTMLBody)); err != nil {
		return buf, "", err
	}
	_ = qp.Close()
	buf.WriteString("\r\n")

	// Attachments
	for _, a := range m.Attachments {
		fmt.Fprintf(&buf, "--%s\r\nContent-Type: %s\r\n"+
			"Content-Disposition: attachment; filename=\"%s\"\r\n"+
			"Content-Transfer-Encoding: base64\r\n\r\n",
			boundary, a.ContentType, a.Filename)
		// Wrap base64 at 76 cols per RFC 2045.
		encoded := base64.StdEncoding.EncodeToString(a.Data)
		for i := 0; i < len(encoded); i += 76 {
			end := i + 76
			if end > len(encoded) {
				end = len(encoded)
			}
			buf.WriteString(encoded[i:end])
			buf.WriteString("\r\n")
		}
	}
	fmt.Fprintf(&buf, "--%s--\r\n", boundary)
	return buf, boundary, nil
}

func randomBoundary() string {
	var b [20]byte
	_, _ = rand.Read(b[:])
	return "med-" + strings.ToLower(base64.RawURLEncoding.EncodeToString(b[:]))
}

// MockSender records every Send call for tests / dev-without-SMTP.
type MockSender struct {
	Sent []Message
}

func (m *MockSender) Send(msg Message) error {
	m.Sent = append(m.Sent, msg)
	log.Printf("[mail-mock] to=%s subject=%q attachments=%d", msg.To, msg.Subject, len(msg.Attachments))
	return nil
}
