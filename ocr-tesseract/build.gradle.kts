import de.undercouch.gradle.tasks.download.Download

plugins {
    application
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("de.undercouch.download") version "5.6.0"
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

repositories {
    mavenCentral()
}

// Version constants - tessdata version should be compatible with tesseract version
val tess4jVersion = "5.17.0"
val tesseractPlatformVersion = "5.5.1-1.5.12"
val leptonicaPlatformVersion = "1.85.0-1.5.12"
val tessdataVersion = "4.1.0" // tessdata 4.1.0 is compatible with Tesseract 4.x and 5.x

dependencies {
    implementation(project(":ocr-common"))
    implementation("net.sourceforge.tess4j:tess4j:$tess4jVersion")
    implementation("org.bytedeco:tesseract-platform:$tesseractPlatformVersion")
    implementation("org.bytedeco:leptonica-platform:$leptonicaPlatformVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    applicationName = "ocr-tesseract"
    mainClass.set("com.jonnyzzz.intellij.mcp.ocr.app.OcrCliKt")
}

kotlin {
    jvmToolchain(21)
}

// Tessdata download configuration
val tessdataFiles = listOf("eng.traineddata", "osd.traineddata")
val tessdataDownloadDir = layout.buildDirectory.dir("tessdata-download")
val tessdataDir = layout.buildDirectory.dir("tessdata")

// Download tessdata files
val downloadTessdata by tasks.registering(Download::class) {
    description = "Download Tesseract trained data files"

    val baseUrl = "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion"
    src(tessdataFiles.map { "$baseUrl/$it" })
    dest(tessdataDownloadDir)
    overwrite(true)
    onlyIfModified(true)
}

// Sync tessdata to clean directory (removes old artifacts)
val syncTessdata by tasks.registering(Sync::class) {
    description = "Sync tessdata files to distribution directory"
    dependsOn(downloadTessdata)

    from(tessdataDownloadDir)
    into(tessdataDir)

    // Inputs for up-to-date checking
    inputs.property("tessdataVersion", tessdataVersion)
    inputs.property("tessdataFiles", tessdataFiles.joinToString(","))
}

// Include tessdata in the distribution
distributions {
    main {
        contents {
            from(syncTessdata) {
                into("tessdata")
            }
        }
    }
}

// Ensure tessdata is synced before building the distribution
tasks.named("installDist") {
    dependsOn(syncTessdata)
}

tasks.named("distZip") {
    dependsOn(syncTessdata)
}

tasks.named("distTar") {
    dependsOn(syncTessdata)
}
