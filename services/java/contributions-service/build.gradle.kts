plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules-engine"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Apache POI — used by StatementExcelService to render statement workbooks.
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")

    // Shared Testcontainers fixtures (Postgres + Kafka bases, @WithTenant)
    testImplementation(testFixtures(project(":shared")))
    // Flyway 10 split Postgres support out of flyway-core. SchemeServiceIT enables
    // Flyway via test-migration/V001__schemes.sql; without this module the IT fails
    // with "Unsupported Database: PostgreSQL 17" on the Testcontainers Postgres.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
