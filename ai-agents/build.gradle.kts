plugins {
    kotlin("jvm") version "2.2.21"
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
