plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("org.springframework.boot:spring-boot-starter-data-r2dbc")
    api("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    api("io.projectreactor.kafka:reactor-kafka:1.3.23")
    api("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.security:spring-security-oauth2-resource-server")
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.0")
    api("com.fasterxml.jackson.core:jackson-databind")

    api("io.micrometer:micrometer-tracing-bridge-otel")
    api("io.opentelemetry:opentelemetry-exporter-otlp")

    // Actuator on the compile classpath so shared modules can declare
    // ReactiveHealthIndicator beans (e.g. R2dbcPoolHealthIndicator).
    // Each service still declares actuator as runtimeOnly for endpoint wiring.
    api("org.springframework.boot:spring-boot-starter-actuator")

    // Permission enforcement: AOP for @RequiresPermission, Caffeine for the
    // per-(tenant,user) permission cache with 60-s TTL.
    api("org.springframework.boot:spring-boot-starter-aop")
    api("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // JavaMoney / Moneta — used by shared.currency.Money and CurrencyConverter.
    api("javax.money:money-api:1.1")
    api("org.javamoney:moneta:1.4.4")

    // Jakarta Validation — hosts shared JSR-303 annotations (@FirstOfMonth,
    // @EndOfMonth) so every downstream DTO can use them without pulling
    // the starter itself.
    api("org.springframework.boot:spring-boot-starter-validation")

    // Apache POI — backs shared.report.ReportWorkbook so every service that
    // exports XLSX from a report endpoint uses one builder and one set of
    // cell styles. Both contributions-service and finance-service previously
    // declared this directly; they now pick it up transitively.
    api("org.apache.poi:poi-ooxml:5.2.5")

    // MinIO SDK — backs shared.kafka.MinIOPayloadStore (Phase 15 §10 / I25).
    // Report-job chunk payloads over ~900 KB are streamed to the
    // medfund-report-payloads bucket rather than pushed inline through Kafka.
    // The MinIOConfig bean is @ConditionalOnProperty("minio.endpoint"), so
    // services that don't opt in never construct a client — the class stays
    // on the classpath for everyone.
    api("io.minio:minio:8.5.11")

    // ── Test fixtures (java-test-fixtures plugin) ─────────────────────────
    // Downstream services pull these via:
    //   testImplementation(testFixtures(project(":shared")))
    // Versions are managed by the testcontainers-bom imported in the root
    // build.gradle.kts subprojects { } block.
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
    testFixturesApi("org.testcontainers:kafka")
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("io.projectreactor:reactor-test")
    testFixturesApi("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testFixturesApi("org.flywaydb:flyway-core")
    testFixturesApi("org.postgresql:postgresql")
    testFixturesApi("org.apache.kafka:kafka-clients")
}
