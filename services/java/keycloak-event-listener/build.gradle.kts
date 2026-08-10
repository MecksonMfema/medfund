plugins {
    java
}

group = "com.medfund"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Keycloak SPI interfaces — provided by Keycloak at runtime, NOT bundled in the JAR
    compileOnly("org.keycloak:keycloak-server-spi:24.0.4")
    compileOnly("org.keycloak:keycloak-server-spi-private:24.0.4")
    compileOnly("org.keycloak:keycloak-core:24.0.4")

    // Kafka client — bundled so it is available inside the Keycloak JVM
    implementation("org.apache.kafka:kafka-clients:3.7.1")

    // Jackson — bundled for JSON serialization of event payloads
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // Tests — JUnit 5 + Mockito, plus the Keycloak SPI on the test classpath
    // (compileOnly deps are not visible to tests).
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.keycloak:keycloak-server-spi:24.0.4")
    testImplementation("org.keycloak:keycloak-server-spi-private:24.0.4")
    testImplementation("org.keycloak:keycloak-core:24.0.4")
}

tasks.test {
    useJUnitPlatform()
}

// Fat JAR: bundle Kafka + Jackson so Keycloak finds them alongside the SPI classes.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "MedFund Keycloak Event Listener",
            "Implementation-Version" to version,
        )
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Strip signatures and multi-release module-info entries that cause issues in fat JARs
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/*/module-info.class")
}
