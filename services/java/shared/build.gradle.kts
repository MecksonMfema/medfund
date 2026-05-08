plugins {
    `java-library`
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
}
