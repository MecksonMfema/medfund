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
