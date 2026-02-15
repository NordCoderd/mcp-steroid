import org.gradle.api.attributes.Usage

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

// Expose the fat JAR for deployment into Docker containers
val fatJarElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "fat-jar"))
    }
}

artifacts {
    add(fatJarElements.name, tasks.jar)
}

kotlin {
    jvmToolchain(21)
}
