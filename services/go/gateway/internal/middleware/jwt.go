package middleware

import (
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"

	"github.com/medfund/gateway/internal/events"
)

// JWTMiddleware validates JWT tokens against Keycloak's JWKS endpoint.
// It caches public keys and refreshes them hourly.
type JWTMiddleware struct {
	keycloakURL string
	realm       string
	publicKeys  map[string]*rsa.PublicKey
	mu          sync.RWMutex
	lastFetch   time.Time
	publisher   *events.Publisher // nil = security event publishing disabled
}

// NewJWTMiddleware creates a new JWT validation middleware for the given Keycloak instance.
func NewJWTMiddleware(keycloakURL, realm string, publisher *events.Publisher) *JWTMiddleware {
	return &JWTMiddleware{
		keycloakURL: keycloakURL,
		realm:       realm,
		publicKeys:  make(map[string]*rsa.PublicKey),
		publisher:   publisher,
	}
}

// Handler returns a Fiber handler that validates JWT tokens on every request,
// skipping health and swagger endpoints. It extracts the token from cookies first,
// falling back to the Authorization header.
func (j *JWTMiddleware) Handler() fiber.Handler {
	return func(c *fiber.Ctx) error {
		// Skip auth for health, swagger, public endpoints
		path := c.Path()
		if strings.HasPrefix(path, "/health") || strings.HasPrefix(path, "/swagger") {
			return c.Next()
		}

		tokenStr := j.extractToken(c)
		if tokenStr == "" {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error": "missing or invalid authorization token",
			})
		}

		claims, err := j.validateToken(tokenStr)
		if err != nil {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error": "invalid token: " + err.Error(),
			})
		}

		// Store claims and raw token in context
		c.Locals("jwt_claims", claims)
		c.Locals("user_id", claims["sub"])
		c.Locals("jwt_token", tokenStr)

		// Extract tenant from JWT
		if tenantID, ok := claims["tenant_id"].(string); ok && tenantID != "" {
			c.Locals("tenant_id", tenantID)
			c.Request().Header.Set("X-Tenant-ID", tenantID)
		}

		return c.Next()
	}
}

// SessionHandler validates a Bearer token, sets it as an HTTP-only cookie,
// and publishes a LOGIN security event to Kafka.
func (j *JWTMiddleware) SessionHandler() fiber.Handler {
	return func(c *fiber.Ctx) error {
		auth := c.Get("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "Bearer token required"})
		}
		tokenStr := auth[7:]
		claims, err := j.validateToken(tokenStr)
		if err != nil {
			// Publish failed-login event before rejecting
			j.publishSecurityEvent(c, claims, "LOGIN_ERROR", "invalid or expired token")
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "invalid token"})
		}

		maxAge := 300
		if expFloat, ok := claims["exp"].(float64); ok {
			remaining := int(expFloat) - int(time.Now().Unix())
			if remaining > 0 {
				maxAge = remaining
			}
		}

		isRefresh := c.Cookies("access_token") != ""

		c.Cookie(&fiber.Cookie{
			Name:     "access_token",
			Value:    tokenStr,
			HTTPOnly: true,
			Secure:   false,
			SameSite: "Lax",
			MaxAge:   maxAge,
			Path:     "/",
		})

		// Only publish LOGIN on first session establishment.
		// Token refreshes (existing cookie present) are silent — not login events.
		if !isRefresh {
			j.publishSecurityEvent(c, claims, "LOGIN", "")
		}
		return c.JSON(fiber.Map{"status": "ok"})
	}
}

// LogoutHandler clears the HTTP-only session cookie and publishes a LOGOUT event.
func (j *JWTMiddleware) LogoutHandler() fiber.Handler {
	return func(c *fiber.Ctx) error {
		// Read claims from the outgoing cookie before clearing it
		var claims jwt.MapClaims
		if tokenStr := c.Cookies("access_token"); tokenStr != "" {
			claims, _ = j.validateToken(tokenStr)
		}

		c.Cookie(&fiber.Cookie{
			Name:     "access_token",
			Value:    "",
			HTTPOnly: true,
			Secure:   false,
			SameSite: "Lax",
			MaxAge:   -1,
			Path:     "/",
		})

		j.publishSecurityEvent(c, claims, "LOGOUT", "")
		return c.JSON(fiber.Map{"status": "ok"})
	}
}

// publishSecurityEvent sends a fire-and-forget event to the Kafka security topic.
// No-ops when publisher is nil or claims are empty.
func (j *JWTMiddleware) publishSecurityEvent(c *fiber.Ctx, claims jwt.MapClaims, eventType, details string) {
	if j.publisher == nil {
		return
	}

	userID := ""
	actorEmail := ""
	tenantID := ""

	if claims != nil {
		if sub, ok := claims["sub"].(string); ok {
			userID = sub
		}
		if email, ok := claims["email"].(string); ok {
			actorEmail = email
		} else if username, ok := claims["preferred_username"].(string); ok {
			actorEmail = username
		}
		if tid, ok := claims["tenant_id"].(string); ok {
			tenantID = tid
		} else if realm, ok := claims["iss"].(string); ok {
			// Extract realm name from issuer URL: .../realms/{realm}
			parts := strings.Split(realm, "/realms/")
			if len(parts) == 2 {
				tenantID = parts[1]
			}
		}
	}

	j.publisher.Publish(events.SecurityEvent{
		TenantID:   tenantID,
		EventType:  eventType,
		UserID:     userID,
		ActorEmail: actorEmail,
		IPAddress:  c.IP(),
		UserAgent:  c.Get("User-Agent"),
		Details:    details,
	})
}

// extractToken retrieves the JWT from cookies (preferred) or Authorization header.
func (j *JWTMiddleware) extractToken(c *fiber.Ctx) string {
	// Cookie first
	if token := c.Cookies("access_token"); token != "" {
		return token
	}
	// Authorization header fallback
	auth := c.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		return auth[7:]
	}
	return ""
}

// validateToken parses and validates a JWT string against Keycloak's public keys.
func (j *JWTMiddleware) validateToken(tokenStr string) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenStr, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		kid, _ := token.Header["kid"].(string)
		key, err := j.getPublicKey(kid)
		if err != nil {
			return nil, err
		}
		return key, nil
	})
	if err != nil {
		return nil, err
	}
	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok || !token.Valid {
		return nil, fmt.Errorf("invalid token claims")
	}
	return claims, nil
}

// getPublicKey returns the RSA public key for the given key ID, fetching from
// Keycloak's JWKS endpoint if the cache is empty or stale (older than 1 hour).
func (j *JWTMiddleware) getPublicKey(kid string) (*rsa.PublicKey, error) {
	j.mu.RLock()
	if key, ok := j.publicKeys[kid]; ok && time.Since(j.lastFetch) < 1*time.Hour {
		j.mu.RUnlock()
		return key, nil
	}
	j.mu.RUnlock()

	// Fetch JWKS
	j.mu.Lock()
	defer j.mu.Unlock()

	// Double-check after acquiring write lock
	if key, ok := j.publicKeys[kid]; ok && time.Since(j.lastFetch) < 1*time.Hour {
		return key, nil
	}

	url := fmt.Sprintf("%s/realms/%s/protocol/openid-connect/certs", j.keycloakURL, j.realm)
	resp, err := http.Get(url)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch JWKS: %w", err)
	}
	defer resp.Body.Close()

	var jwks struct {
		Keys []struct {
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&jwks); err != nil {
		return nil, fmt.Errorf("failed to decode JWKS: %w", err)
	}

	for _, k := range jwks.Keys {
		nBytes, _ := base64.RawURLEncoding.DecodeString(k.N)
		eBytes, _ := base64.RawURLEncoding.DecodeString(k.E)
		e := new(big.Int).SetBytes(eBytes).Int64()
		j.publicKeys[k.Kid] = &rsa.PublicKey{
			N: new(big.Int).SetBytes(nBytes),
			E: int(e),
		}
	}
	j.lastFetch = time.Now()

	if key, ok := j.publicKeys[kid]; ok {
		return key, nil
	}
	return nil, fmt.Errorf("key %s not found in JWKS", kid)
}
