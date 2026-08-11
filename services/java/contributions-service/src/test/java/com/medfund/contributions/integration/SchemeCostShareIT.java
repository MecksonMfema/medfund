package com.medfund.contributions.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.CreateBenefitCostShareRequest;
import com.medfund.contributions.dto.CreateBenefitCostShareTierRequest;
import com.medfund.contributions.dto.CreateSchemeCostShareRequest;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeBenefit;
import com.medfund.contributions.repository.SchemeBenefitRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.contributions.service.SchemeCostShareService;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-level IT for the Phase 1 cost-share configuration surface. Covers:
 * <ul>
 *   <li>The tenant-side V076 tables exist in the test schema (mirrored under
 *       {@code db/test-migration/V002}).</li>
 *   <li>POST → GET roundtrip returns the newly-inserted row as the effective one.</li>
 *   <li>Temporal overlap resolves to the most-recent-effective row.</li>
 *   <li>Audit envelope reaches Kafka with a friendly entityName (never the UUID)
 *       and actorEmail populated end-to-end.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.locations=classpath:db/test-migration",
    "spring.flyway.baseline-on-migrate=true"
})
@Import(SchemeCostShareIT.SecurityStub.class)
class SchemeCostShareIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                token, Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "test", "iss", "test")
            ));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    @Autowired private SchemeCostShareService schemeCostShareService;
    @Autowired private SchemeRepository schemeRepository;
    @Autowired private SchemeBenefitRepository schemeBenefitRepository;

    @Test
    @WithTenant(TENANT_ID)
    void createScheme_persistsRow_emitsAudit_and_readsBack() {
        UUID schemeId = createScheme("SchemeCS_" + UUID.randomUUID(), "USD");

        var actorId = UUID.randomUUID().toString();
        var actorEmail = "actor@test.example";
        var request = new CreateSchemeCostShareRequest(
                2026,
                new BigDecimal("500.00"),
                new BigDecimal("2500.00"),
                "INDIVIDUAL",
                "INDIVIDUAL",
                "RECOVER_FROM_MEMBER",
                "USD",
                LocalDate.of(2026, 1, 1),
                null);

        var saved = schemeCostShareService.createScheme(schemeId, request, actorId, actorEmail)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getShortfallPolicy()).isEqualTo("RECOVER_FROM_MEMBER");

        // GET as-of a covered date returns the row.
        var effective = schemeCostShareService
                .findEffectiveScheme(schemeId, 2026, LocalDate.of(2026, 6, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(effective).isNotNull();
        assertThat(effective.getId()).isEqualTo(saved.getId());
        assertThat(effective.getDeductible()).isEqualByComparingTo(new BigDecimal("500.00"));

        // GET as-of a pre-effective date returns nothing.
        var beforeEffective = schemeCostShareService
                .findEffectiveScheme(schemeId, 2026, LocalDate.of(2025, 12, 31))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(2));
        assertThat(beforeEffective).isNull();

        // KafkaContainer is reused across the JVM — other test runs' events sit on the
        // same topic. Filter by our own entityId so we know we've found *our* event.
        JsonNode event = consumeAuditEventFor("medfund.audit.events", "SchemeCostShare",
                saved.getId().toString(), Duration.ofSeconds(10));
        assertThat(event).as("audit event for our SchemeCostShare CREATE should reach Kafka").isNotNull();
        assertThat(event.path("action").asText()).isEqualTo("CREATE");
        assertThat(event.path("actorId").asText()).isEqualTo(actorId);
        assertThat(event.path("actorEmail").asText()).isEqualTo(actorEmail);
        // Friendly entityName — never the UUID (feedback_audit_entity_name).
        String entityName = event.path("entityName").asText();
        assertThat(entityName).contains("cost-share").contains("2026");
        assertThat(entityName).doesNotContain(saved.getId().toString());
    }

    /**
     * Consume until an event on {@code topic} matches both {@code entityType}
     * and the given {@code entityId}. The reused Kafka container carries state
     * across suites, so filtering only by entityType returns whichever event
     * showed up first — usually a stranger.
     */
    private JsonNode consumeAuditEventFor(String topic, String entityType, String entityId, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        ObjectMapper mapper = new ObjectMapper();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = mapper.readTree(rec.value());
                        if (entityType.equals(node.path("entityType").asText())
                                && entityId.equals(node.path("entityId").asText())) {
                            return node;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    @Test
    @WithTenant(TENANT_ID)
    void overlappingWindows_resolveToMostRecentEffectiveFrom() {
        UUID schemeId = createScheme("SchemeCS_overlap_" + UUID.randomUUID(), "USD");
        var actor = UUID.randomUUID().toString();

        // Insert the original row (January 1st effective).
        schemeCostShareService.createScheme(schemeId,
                        new CreateSchemeCostShareRequest(2026,
                                new BigDecimal("500.00"), new BigDecimal("2500.00"),
                                "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER",
                                "USD", LocalDate.of(2026, 1, 1), null),
                        actor, "actor@test.example")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        // Superseding row: deductible bumped to 750, effective mid-year.
        var supersede = schemeCostShareService.createScheme(schemeId,
                        new CreateSchemeCostShareRequest(2026,
                                new BigDecimal("750.00"), new BigDecimal("2500.00"),
                                "INDIVIDUAL", "INDIVIDUAL", "RECOVER_FROM_MEMBER",
                                "USD", LocalDate.of(2026, 6, 1), null),
                        actor, "actor@test.example")
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(supersede).isNotNull();

        // A read for August returns the newer row.
        var effective = schemeCostShareService
                .findEffectiveScheme(schemeId, 2026, LocalDate.of(2026, 8, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(effective).isNotNull();
        assertThat(effective.getId()).isEqualTo(supersede.getId());
        assertThat(effective.getDeductible()).isEqualByComparingTo(new BigDecimal("750.00"));

        // A read for March still returns the original.
        var earlier = schemeCostShareService
                .findEffectiveScheme(schemeId, 2026, LocalDate.of(2026, 3, 1))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(earlier).isNotNull();
        assertThat(earlier.getDeductible()).isEqualByComparingTo(new BigDecimal("500.00"));

        // History returns both, newest first.
        var history = schemeCostShareService.findSchemeHistory(schemeId, 2026)
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(history.get(1).getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @WithTenant(TENANT_ID)
    void createBenefitCostShare_withTiers_roundTrips() {
        UUID schemeId = createScheme("SchemeCS_benefit_" + UUID.randomUUID(), "USD");
        UUID benefitId = createBenefit(schemeId, "Outpatient GP");

        var actor = UUID.randomUUID().toString();
        var actorEmail = "benefit@test.example";
        var benefitReq = new CreateBenefitCostShareRequest(
                "TIERED",
                null,
                null,
                new BigDecimal("50.00"),
                new BigDecimal("0.2000"),
                true, true, "per_visit",
                LocalDate.of(2026, 1, 1),
                null);

        var savedBenefit = schemeCostShareService.createBenefit(benefitId, benefitReq, actor, actorEmail)
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));
        assertThat(savedBenefit).isNotNull();
        assertThat(savedBenefit.getCopayType()).isEqualTo("TIERED");
        assertThat(savedBenefit.getAppliesToDeductible()).isTrue();

        // Add two tiers.
        var tier1 = new CreateBenefitCostShareTierRequest(
                "TIER_1", new BigDecimal("10.00"), null, null);
        var tier2 = new CreateBenefitCostShareTierRequest(
                "TIER_2", new BigDecimal("25.00"), null, null);
        schemeCostShareService.createTier(savedBenefit.getId(), tier1, actor, actorEmail)
                .then(schemeCostShareService.createTier(savedBenefit.getId(), tier2, actor, actorEmail))
                .contextWrite(TenantTestContext.put())
                .block(Duration.ofSeconds(10));

        var tiers = schemeCostShareService.findTiers(savedBenefit.getId())
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(tiers).extracting("tierName").containsExactlyInAnyOrder("TIER_1", "TIER_2");
    }

    // ── Helpers — insert directly via repositories so this IT doesn't drag ───
    //    the tariff-category dependencies SchemeService.createBenefit needs.

    private UUID createScheme(String name, String currency) {
        var scheme = new Scheme();
        scheme.setName(name);
        scheme.setSchemeType("hmo");
        scheme.setInsuranceLine("HEALTH");
        scheme.setStatus("active");
        scheme.setEffectiveDate(LocalDate.now());
        scheme.setCurrencyCode(currency);
        scheme.setCreatedAt(Instant.now());
        scheme.setUpdatedAt(Instant.now());
        var saved = schemeRepository.save(scheme).block(Duration.ofSeconds(10));
        assertThat(saved).isNotNull();
        return saved.getId();
    }

    private UUID createBenefit(UUID schemeId, String name) {
        var benefit = new SchemeBenefit();
        benefit.setSchemeId(schemeId);
        benefit.setName(name);
        benefit.setBenefitType("OUTPATIENT");
        benefit.setAnnualLimit(new BigDecimal("1000.00"));
        benefit.setCurrencyCode("USD");
        benefit.setCreatedAt(Instant.now());
        benefit.setUpdatedAt(Instant.now());
        var saved = schemeBenefitRepository.save(benefit).block(Duration.ofSeconds(10));
        assertThat(saved).isNotNull();
        return saved.getId();
    }
}
