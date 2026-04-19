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
}
