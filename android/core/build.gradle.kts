// Чиста Kotlin/JVM библиотека, без Android зависимости.
// Компилира се и се тества самостоятелно:  ./gradlew -p core test
// Тестове срещу живите API-та (изисква интернет):  LIVE=1 ./gradlew -p core test --tests '*LiveFeedsTest*'
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "bg.drone"
version = "0.2.0"

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    environment("LIVE", System.getenv("LIVE") ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = System.getenv("LIVE") == "1"
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
