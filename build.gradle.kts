// Copyright (C) 2026 Alberto Lirussi — AGPL 3.0

plugins {
    java
    application
    jacoco
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.codingful"
version = System.getenv("VERSION") ?: "dev-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val spoonVersion    = "11.4.0"
val jgraphtVersion  = "1.5.2"
val jacksonVersion  = "3.2.0"
val slf4jVersion    = "2.0.13"
val logbackVersion  = "1.5.6"
val junitVersion    = "6.0.3"
val assertjVersion  = "3.27.7"

dependencies {
    implementation("fr.inria.gforge.spoon:spoon-core:$spoonVersion")
    implementation("org.jgrapht:jgrapht-core:$jgraphtVersion")
    implementation("org.jgrapht:jgrapht-io:$jgraphtVersion")
    implementation("tools.jackson.core:jackson-databind:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")

    // JUnit 6 uses unified versioning across Platform/Jupiter/Vintage — the BOM
    // keeps junit-jupiter and junit-platform-launcher aligned to one version.
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.codingful.carve.Carve"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit { counter = "INSTRUCTION"; minimum = "0.90".toBigDecimal() }
            limit { counter = "BRANCH";      minimum = "0.80".toBigDecimal() }
        }
    }
}

// `./gradlew check` fails if coverage regresses below the thresholds above.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    mergeServiceFiles()
    // Ship attribution/notices for the bundled third-party libraries.
    from("THIRD-PARTY-NOTICES.md")
    from("LICENSE")
}

tasks.named("startScripts") {
    dependsOn(tasks.named("shadowJar"))
}
