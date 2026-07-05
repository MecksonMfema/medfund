plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules-engine"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")
    // Testcontainers + shared IT harness (AbstractPostgresIntegrationTest,
    // TenantTestContext). Pulled from shared's testFixtures set —
    // matches the pattern contributions-service uses for its ITs.
    testImplementation(testFixtures(project(":shared")))
    // Flyway 10 split its Postgres support into a separate artifact; on
    // Postgres 17 the core loader throws "Unsupported Database" without
    // this. See memory: infra_testcontainers_pitfalls.
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
}

// Disable Gradle's build cache for compileJava in this module. Spring Boot
// devtools restarts watch user-service/build/classes; when cached compile
// outputs get served stale (especially during fast iteration with many
// edits + restarts), Spring boots against an outdated classpath and fails
// with NoClassDefFoundError on classes the source clearly defines.
// Forcing recompilation keeps the iteration loop honest.
tasks.withType<JavaCompile>().configureEach {
    outputs.cacheIf { false }
}
