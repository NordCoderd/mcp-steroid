plugins {
    kotlin("jvm") version "2.3.10"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
}

kotlin {
    jvmToolchain(21)
}
