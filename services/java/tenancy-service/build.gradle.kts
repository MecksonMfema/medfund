plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")

    // Shared Testcontainers fixtures
    testImplementation(testFixtures(project(":shared")))
    // okhttp MockWebServer stands in for the Keycloak Admin REST API in
    // KeycloakRealmSyncIT. Same version as finance-service, which uses it for
    // the claims/contributions peer stubs.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
