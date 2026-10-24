plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules-engine"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")
    // Testcontainers + shared IT harness (AbstractPostgresIntegrationTest,
    // TenantTestContext). Pulled from shared's testFixtures set — matches the
    // pattern user-service + contributions-service use for their ITs.
    testImplementation(testFixtures(project(":shared")))
    // Flyway 10 split its Postgres support into a separate artifact; on
    // Postgres 17 the core loader throws "Unsupported Database" without
    // this. See memory: infra_testcontainers_pitfalls.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
}

// Disable Gradle's build cache for compileJava in this module — same reason
// as user-service: devtools restarts watch build/classes, and a stale cached
// compile output boots the service against an outdated classpath.
tasks.withType<JavaCompile>().configureEach {
    outputs.cacheIf { false }
}
