// Copyright (C) 2026 Alberto Lirussi — AGPL 3.0

plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.codingful"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val spoonVersion    = "11.2.1"
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
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    mergeServiceFiles()
}
