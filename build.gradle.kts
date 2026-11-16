plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "estate"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("no.nav.security:mock-oauth2-server:7.0.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val webDir = layout.projectDirectory.dir("web")
val skipWeb = providers.gradleProperty("skipWeb").isPresent

val npmCi = tasks.register<Exec>("npmCi") {
    workingDir = webDir.asFile
    commandLine("npm", "ci")
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
    onlyIf { !skipWeb }
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    dependsOn(npmCi)
    workingDir = webDir.asFile
    commandLine("npm", "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.files(
        webDir.file("index.html"),
        webDir.file("package.json"),
        webDir.file("package-lock.json"),
        webDir.file("vite.config.ts"),
    )
    // tsc --noEmit이 tests와 다른 설정 파일까지 검사하고, public은 dist로 복사된다
    inputs.files(fileTree(webDir) { include("tsconfig*.json", "*.config.ts", "tests/**", "public/**") })
    outputs.dir(webDir.dir("dist"))
    onlyIf { !skipWeb }
}

tasks.processResources {
    dependsOn(npmBuild)
    from(webDir.dir("dist")) { into("static") }
}
