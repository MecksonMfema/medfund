package com.medfund.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityEventMessageTest {

    @Test
    void recordAccessorsExposeAllFields() {
        SecurityEventMessage msg = new SecurityEventMessage(
                "id-1", "tenant-1", "DATA_ACCESS", "user-1", "user@example.com",
                "10.0.0.1", "curl", "{\"k\":\"v\"}", "2026-08-11T00:00:00Z");

        assertThat(msg.id()).isEqualTo("id-1");
        assertThat(msg.tenantId()).isEqualTo("tenant-1");
        assertThat(msg.eventType()).isEqualTo("DATA_ACCESS");
        assertThat(msg.userId()).isEqualTo("user-1");
        assertThat(msg.actorEmail()).isEqualTo("user@example.com");
        assertThat(msg.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(msg.userAgent()).isEqualTo("curl");
        assertThat(msg.details()).isEqualTo("{\"k\":\"v\"}");
        assertThat(msg.timestamp()).isEqualTo("2026-08-11T00:00:00Z");
    }
}
