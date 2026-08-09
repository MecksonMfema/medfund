plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules-engine"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")

    // Shared Testcontainers fixtures (Postgres + Kafka bases, @WithTenant).
    // Landed with CtcPaymentServiceIT — Phase 3 extends the same harness for
    // the CTC offset round-trip test.
    testImplementation(testFixtures(project(":shared")))
    // Flyway 10 split Postgres support out of flyway-core. See
    // contributions-service/build.gradle.kts for the same guard.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
