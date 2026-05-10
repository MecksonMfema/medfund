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
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
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
    }

    // Spring Boot DevTools: only on subprojects that apply the Spring Boot plugin
    // (the `developmentOnly` configuration is created by that plugin).
    pluginManager.withPlugin("org.springframework.boot") {
        dependencies {
            "developmentOnly"("org.springframework.boot:spring-boot-devtools")
        }
    }
}
