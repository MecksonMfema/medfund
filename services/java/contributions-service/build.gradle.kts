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
}
