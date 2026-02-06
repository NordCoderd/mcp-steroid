import de.undercouch.gradle.tasks.download.Download

plugins {
    kotlin("jvm") version "2.2.21"
    id("de.undercouch.download") version "5.6.0"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

// Resolvable configuration to get the plugin .zip from root project
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

dependencies {
    pluginZip(project(":"))

    testImplementation(project(":test-helper"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

kotlin {
    jvmToolchain(21)
}

// IntelliJ IDEA Ultimate download (always x86_64 for Docker container)
// CE 2025.3 is not released; plugin requires sinceBuild=253, so we use Ultimate
val ideVersion = "2025.3.2"
val ideFileName = "ideaIU-${ideVersion}.tar.gz"
val ideUrl = "https://download.jetbrains.com/idea/$ideFileName"
val ideDownloadDir = layout.buildDirectory.dir("idea-download")

val downloadIdea by tasks.registering(Download::class) {
    group = "ide"
    description = "Download IntelliJ IDEA Community Edition"
    src(ideUrl)
    dest(ideDownloadDir.map { it.file(ideFileName) })
    onlyIfModified(true)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Plugin zip path resolved via Gradle configuration
    dependsOn(pluginZip)
    dependsOn(downloadIdea)
    doFirst {
        systemProperty("test.integration.plugin.zip", pluginZip.singleFile.absolutePath)
        systemProperty("test.integration.idea.archive", downloadIdea.get().dest.absolutePath)
    }

    // Pass through optional properties
    listOf(
        "test.integration.video.output",
        "test.integration.docker.keep",
    ).forEach { prop ->
        System.getProperty(prop)?.let { systemProperty(prop, it) }
    }
}
