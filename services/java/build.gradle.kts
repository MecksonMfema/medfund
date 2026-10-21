plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.medfund"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Preserve method-parameter names in compiled bytecode so Spring can
    // resolve @RequestParam / @PathVariable bindings without requiring an
    // explicit `name = "..."` on every annotation. Without this, any bare
    // @RequestParam falls back to reflection and fails with
    // "parameter name information not available via reflection".
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        // Spring Boot 3.3.5 pins testcontainers to 1.19.8, which ships with
        // docker-java 3.3.6. That client negotiates Docker API 1.32 which is
        // below the minimum (1.44) required by current Docker Engine builds —
        // Testcontainers fails to start with "client version 1.32 is too old".
        // Overriding the BOM-managed property forces 1.21.4 (docker-java 3.5.x)
        // which negotiates the newer Engine API.
        extensions.extraProperties["testcontainers.version"] = "1.21.4"
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
            mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        }
    }

    dependencies {
        // Lombok — version managed by Spring Boot BOM
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")

        "implementation"("org.slf4j:slf4j-api")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("io.projectreactor:reactor-test")
        "testImplementation"("org.mockito:mockito-core")
        "testImplementation"("org.mockito:mockito-junit-jupiter")
        "testImplementation"("org.springframework.security:spring-security-test")
    }

    tasks.test {
        useJUnitPlatform()
        // Cap the test worker heap high enough to hold a full Spring Boot
        // context + Testcontainers Postgres/Kafka producer & consumer buffers
        // + Jacoco agent + KieContainer classloader. Gradle's default 512m
        // OOMs the finance-service IT suite (kafka-coordinator-heartbeat
        // threads throw OOM in the middle of a run — Phase 12 §0 discovery).
        maxHeapSize = "1536m"
        // `make test-integration` filters every module with `--tests '*IT'`;
        // modules without ITs (e.g. shared) must not fail on zero matches.
        filter {
            isFailOnNoMatchingTests = false
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    // JaCoCo: emit both HTML (humans, opened locally) and XML (Codecov upload).
    // Gate is wired into `check` and enforced at 70% line coverage per service.
    // Several modules sit below that today (see .claude/coverage-backlog.md);
    // closing those gaps is gated work before any new feature on the module.
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("jacocoTestReport"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.70".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(tasks.named("jacocoTestCoverageVerification"))
    }

    // Spring Boot DevTools: only on subprojects that apply the Spring Boot plugin
    // (the `developmentOnly` configuration is created by that plugin).
    pluginManager.withPlugin("org.springframework.boot") {
        dependencies {
            "developmentOnly"("org.springframework.boot:spring-boot-devtools")
        }
        // Cap each dev bootRun forked JVM. Five services at default (~800 MB each)
        // exceed the 30 GiB budget when combined with Gradle daemons, VS Code
        // Java extensions, and Docker infra — systemd-oomd then kills the tree.
        tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
            jvmArgs = listOf(
                "-Xmx384m",
                "-XX:MaxMetaspaceSize=256m",
                "-XX:+UseSerialGC",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:HeapDumpPath=build/heap-dumps/"
            )

            // Consume the internal library projects (:shared, :rules-engine) as
            // raw class + resource directories on the bootRun classpath instead
            // of packaged JARs. Two things break when they ship as JARs in dev:
            //
            //  1. Spring Boot devtools only watches directories on the classpath.
            //     A rebuild of :shared or :rules-engine (e.g. as a side effect of
            //     `:some-service:compileJava`) rewrites the JAR file on disk, but
            //     the running JVM keeps the stale ZipFile handle it opened at
            //     boot. Any lazily-loaded class from the swapped JAR then trips
            //     NoClassDefFoundError on its first reference. Reactor's
            //     throwIfFatal turns that into an uncaught fatal that never
            //     terminates the reactive stream, so callers just time out on
            //     the guarded 2-second per-hop ceiling and the composed report
            //     surfaces "partial data" for something that is actually a JVM
            //     linkage failure.
            //
            //  2. Using class dirs lets devtools trigger a hot restart when the
            //     library source changes, which is the point of having devtools
            //     on the classpath in the first place.
            //
            // Filtering by JAR name (not File equality) is deliberate: the Jar
            // task's archiveFile provider would be resolved eagerly and
            // circumvent Gradle's build-time up-to-date checks. The Boot plugin's
            // own `.jar` output for this subproject stays on the classpath as-is
            // because we only strip the two library artefacts by name.
            classpath = files(
                sourceSets["main"].output,
                project(":shared").sourceSets["main"].output,
                project(":rules-engine").sourceSets["main"].output,
                configurations.runtimeClasspath.get().filter { f ->
                    val n = f.name
                    !((n.startsWith("shared-") || n.startsWith("rules-engine-"))
                            && n.endsWith(".jar"))
                }
            )
            dependsOn(":shared:classes", ":rules-engine:classes")
        }
    }
}
