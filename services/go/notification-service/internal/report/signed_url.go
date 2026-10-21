package report

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

// SignedURLBuilder mints the HMAC-signed download URLs embedded in the
// delivery email when the XLSX is too large to attach. The shared HMAC
// secret is documented in the rollout runbook — finance-service's
// ScheduledDownloadTokenVerifier uses the same secret to verify tokens
// on the download endpoint.
//
// Token shape: base64url(header) "." base64url(payload) "." base64url(sig)
//   header  = {"typ":"SDLT","alg":"HS256"}
//   payload = {jobId, tenantId, recipientEmail, exp (unix seconds)}
//   sig     = HMAC-SHA256(secret, header + "." + payload)
type SignedURLBuilder struct {
	GatewayBaseURL string
	Secret         []byte
	Validity       time.Duration
}

func NewSignedURLBuilder(baseURL, secret string, validity time.Duration) *SignedURLBuilder {
	return &SignedURLBuilder{
		GatewayBaseURL: baseURL,
		Secret:         []byte(secret),
		Validity:       validity,
	}
}

// Build returns (url, expiry, error). Nil receiver or empty secret
// returns an error — the caller degrades the email to a "download link
// unavailable" body rather than sending a broken URL.
func (b *SignedURLBuilder) Build(jobID, tenantID, recipientEmail string) (string, time.Time, error) {
	if b == nil {
		return "", time.Time{}, fmt.Errorf("signed url builder not configured")
	}
	if len(b.Secret) == 0 {
		return "", time.Time{}, fmt.Errorf("signed url builder missing secret")
	}
	expiry := time.Now().Add(b.Validity)
	payload := struct {
		JobID          string `json:"jobId"`
		TenantID       string `json:"tenantId"`
		RecipientEmail string `json:"recipientEmail"`
		Exp            int64  `json:"exp"`
	}{JobID: jobID, TenantID: tenantID, RecipientEmail: recipientEmail, Exp: expiry.Unix()}

	header := b64u([]byte(`{"typ":"SDLT","alg":"HS256"}`))
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("marshal payload: %w", err)
	}
	payloadStr := b64u(payloadBytes)

	mac := hmac.New(sha256.New, b.Secret)
	mac.Write([]byte(header + "." + payloadStr))
	sig := b64u(mac.Sum(nil))

	token := header + "." + payloadStr + "." + sig
	full := fmt.Sprintf("%s/api/v1/reports/scheduled/%s/download?token=%s",
		b.GatewayBaseURL, jobID, token)
	return full, expiry, nil
}

// UnsubscribeURLBuilder produces the URL for the Angular unsubscribe
// confirm page. That page then POSTs to the gateway which proxies to
// tenancy-service's public unsubscribe endpoint.
type UnsubscribeURLBuilder struct {
	WebBaseURL string
}

func NewUnsubscribeURLBuilder(webBaseURL string) *UnsubscribeURLBuilder {
	return &UnsubscribeURLBuilder{WebBaseURL: webBaseURL}
}

func (b *UnsubscribeURLBuilder) Build(token string) string {
	if b == nil || b.WebBaseURL == "" || token == "" {
		return ""
	}
	return fmt.Sprintf("%s/public/unsubscribe/%s", b.WebBaseURL, token)
}

func b64u(bs []byte) string {
	return base64.RawURLEncoding.EncodeToString(bs)
}
