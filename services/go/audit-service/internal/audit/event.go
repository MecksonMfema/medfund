package audit

import "time"

type Event struct {
	ID            string                 `json:"id"`
	TenantID      string                 `json:"tenantId"`
	EntityType    string                 `json:"entityType"`
	EntityID      string                 `json:"entityId"`
	EntityName    string                 `json:"entityName"`
	Action        string                 `json:"action"`
	ActorID       string                 `json:"actorId"`
	ActorEmail    string                 `json:"actorEmail"`
	OldValue      map[string]interface{} `json:"oldValue"`
	NewValue      map[string]interface{} `json:"newValue"`
	ChangedFields []string               `json:"changedFields"`
	CorrelationID string                 `json:"correlationId"`
	Timestamp     time.Time              `json:"timestamp"`
}

type SecurityEvent struct {
	ID         string    `json:"id"`
	TenantID   string    `json:"tenantId"`
	EventType  string    `json:"eventType"` // LOGIN, LOGOUT, LOGIN_ERROR, RESET_PASSWORD, VERIFY_EMAIL, etc.
	UserID     string    `json:"userId"`
	ActorEmail string    `json:"actorEmail"` // email or username resolved by the Keycloak SPI
	IPAddress  string    `json:"ipAddress"`
	UserAgent  string    `json:"userAgent"`
	Details    string    `json:"details"`
	Timestamp  time.Time `json:"timestamp"`
}

// DailyCount holds the event count for a single calendar day (UTC).
type DailyCount struct {
	Date  string `json:"date"`  // "YYYY-MM-DD"
	Count int    `json:"count"`
}

type QueryFilter struct {
	TenantID   string
	EntityType string
	EntityID   string
	Action     string
	ActorID    string
	Query      string // free-text: matches entityType, entityName, entityId, actorEmail, actorId
	StartDate  time.Time
	EndDate    time.Time
	Page       int
	PageSize   int
}
