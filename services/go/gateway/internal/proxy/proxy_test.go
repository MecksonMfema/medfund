package proxy

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gofiber/fiber/v2"
)

// startMockBackend creates a test HTTP server that echoes request details back
// as JSON. This lets us verify the proxy forwards headers, method, path, and body.
func startMockBackend(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		resp := map[string]interface{}{
			"method":   r.Method,
			"path":     r.URL.Path,
			"query":    r.URL.RawQuery,
			"tenantId": r.Header.Get("X-Tenant-ID"),
			"auth":     r.Header.Get("Authorization"),
			"body":     string(body),
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Backend-Header", "from-backend")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(resp)
	}))
}

func TestProxy_ForwardsGETRequest(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("GET", "/api/v1/test/items?page=1", nil)
	req.Header.Set("X-Tenant-ID", "tenant-abc")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["method"] != "GET" {
		t.Fatalf("expected method GET, got %v", result["method"])
	}
	if result["tenantId"] != "tenant-abc" {
		t.Fatalf("expected tenantId tenant-abc, got %v", result["tenantId"])
	}
}

func TestProxy_ForwardsPOSTWithBody(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	reqBody := `{"name":"test claim"}`
	req := httptest.NewRequest("POST", "/api/v1/test/claims", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "tenant-xyz")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["method"] != "POST" {
		t.Fatalf("expected method POST, got %v", result["method"])
	}
	if !strings.Contains(result["body"].(string), "test claim") {
		t.Fatal("expected request body to be forwarded")
	}
}

func TestProxy_ForwardsAuthorizationHeader(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("GET", "/api/v1/test/protected", nil)
	req.Header.Set("X-Tenant-ID", "tenant-1")
	req.Header.Set("Authorization", "Bearer jwt-token-abc123")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["auth"] != "Bearer jwt-token-abc123" {
		t.Fatalf("expected Authorization header to be forwarded, got %v", result["auth"])
	}
}

func TestProxy_ForwardsQueryParameters(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("GET", "/api/v1/test/events?entityType=Member&page=2&pageSize=10", nil)
	req.Header.Set("X-Tenant-ID", "tenant-1")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	query := result["query"].(string)
	if !strings.Contains(query, "entityType=Member") {
		t.Fatalf("expected query params to be forwarded, got %v", query)
	}
}

func TestProxy_CopiesBackendResponseHeaders(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("GET", "/api/v1/test/items", nil)
	req.Header.Set("X-Tenant-ID", "tenant-1")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}

	backendHeader := resp.Header.Get("X-Backend-Header")
	if backendHeader != "from-backend" {
		t.Fatalf("expected backend response header to be copied, got %q", backendHeader)
	}
}

func TestProxy_BackendUnavailable_Returns502(t *testing.T) {
	app := fiber.New()
	// Point to a port that is not listening
	app.All("/api/v1/test/*", Handler("http://127.0.0.1:1"))

	req := httptest.NewRequest("GET", "/api/v1/test/items", nil)
	req.Header.Set("X-Tenant-ID", "tenant-1")

	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 502 {
		t.Fatalf("expected 502 for unavailable backend, got %d", resp.StatusCode)
	}
}

func TestProxy_ForwardsDELETERequest(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("DELETE", "/api/v1/test/items/123", nil)
	req.Header.Set("X-Tenant-ID", "tenant-1")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["method"] != "DELETE" {
		t.Fatalf("expected method DELETE, got %v", result["method"])
	}
}

func TestProxy_ForwardsPUTRequest(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	reqBody := `{"status":"approved"}`
	req := httptest.NewRequest("PUT", "/api/v1/test/claims/456", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "tenant-1")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["method"] != "PUT" {
		t.Fatalf("expected method PUT, got %v", result["method"])
	}
}

func TestProxy_MissingTenantIDNotForwarded(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	req := httptest.NewRequest("GET", "/api/v1/test/items", nil)
	// Intentionally not setting X-Tenant-ID

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	json.Unmarshal(body, &result)

	if result["tenantId"] != "" {
		t.Fatalf("expected empty tenantId when header not set, got %v", result["tenantId"])
	}
}

// TestProxy_ForwardsMultipartBody guards the platform-logo upload path.
//
// fasthttp's Request.CopyTo only carries bodyRaw/body. Once fasthttp has
// parsed a payload into a multipart form, both are empty, so the copied
// request went upstream with a zero-length body but the original
// Content-Length — and the Java backend answered "Could not find first
// boundary" with an opaque 500. The handler now re-sets the body explicitly.
func TestProxy_ForwardsMultipartBody(t *testing.T) {
	backend := startMockBackend(t)
	defer backend.Close()

	app := fiber.New()
	app.All("/api/v1/test/*", Handler(backend.URL))

	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile("file", "logo.png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write([]byte("\x89PNG\r\n\x1a\nfake-bytes")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	sent := buf.String()

	req := httptest.NewRequest("POST", "/api/v1/test/settings/logo", strings.NewReader(sent))
	req.Header.Set("Content-Type", writer.FormDataContentType())

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		t.Fatal(err)
	}

	forwarded, _ := result["body"].(string)
	if forwarded == "" {
		t.Fatal("multipart body was dropped by the proxy")
	}
	if !strings.Contains(forwarded, "--"+writer.Boundary()) {
		t.Fatalf("forwarded body lost its boundary: %q", forwarded)
	}
	if !strings.Contains(forwarded, "fake-bytes") {
		t.Fatalf("forwarded body lost the file part: %q", forwarded)
	}
}
