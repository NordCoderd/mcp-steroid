plugins {
    kotlin("jvm") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ai-agents"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
