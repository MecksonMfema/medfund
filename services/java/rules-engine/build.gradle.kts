// rules-engine: hosts both
//   1. The Drools-backed rule library (TenantRuleEngine, DrlCompiler, model types)
//      that claims-service consumes via `implementation(project(":rules-engine"))`.
//   2. A Spring Boot HTTP layer on port 8086 that serves the tenant rule CRUD
//      endpoints used by the Angular Rules Engine UI.
//
// Both concerns share one source set so the CRUD path can call back into
// TenantRuleEngine.reloadRules(...) for hot-reload after every mutation.

plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.drools:drools-core:9.44.0.Final")
    implementation("org.drools:drools-compiler:9.44.0.Final")
    implementation("org.drools:drools-mvel:9.44.0.Final")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")

    // Shared Testcontainers fixtures (this slice does not need Postgres/Kafka
    // but the dep brings in JUnit Jupiter via the test base, kept consistent
    // across services).
    testImplementation(testFixtures(project(":shared")))
}
