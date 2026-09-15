plugins {
    kotlin("jvm")
}

dependencies {
    // iCalendar parsing - handles RFC 5545 compliance
    // (duplicate-class bug was fixed upstream in 4.0.7, PR #763)
    implementation("org.mnode.ical4j:ical4j:4.3.0")

    // Kotlin coroutines (aligned with KashCal's version catalog)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Testing (JUnit 5)
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
