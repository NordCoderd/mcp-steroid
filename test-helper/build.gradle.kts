plugins {
    kotlin("jvm") version "2.2.21"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}
