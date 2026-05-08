plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules-engine"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.springframework.boot:spring-boot-starter-actuator")
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
