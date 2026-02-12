plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.filter.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.jonnyzzz.mcpSteroid.filter.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

kotlin {
    jvmToolchain(21)
}
