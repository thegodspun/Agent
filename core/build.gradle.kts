import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent agent logic: the Claude tool loop, the benefits/deadline
// tracker, and the reminder rules. Kept free of Android APIs so it can be unit
// tested on a plain JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.anthropic:anthropic-java:2.75.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
