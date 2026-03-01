plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
}

dependencies {
    implementation("com.squareup:kotlinpoet:2.2.0")
}
