import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    application
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
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

// Download tessdata files during build
val tessdataDir = layout.buildDirectory.dir("tessdata")
val tessdataFiles = listOf("eng.traineddata", "osd.traineddata")

val downloadTessdata by tasks.registering {
    description = "Download Tesseract trained data files"

    // Inputs: the version determines which files to download
    inputs.property("tessdataVersion", tessdataVersion)
    inputs.property("tessdataFiles", tessdataFiles.joinToString(","))

    // Outputs: the tessdata directory
    outputs.dir(tessdataDir)

    doLast {
        val dir = tessdataDir.get().asFile
        dir.mkdirs()

        val baseUrl = "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion"

        tessdataFiles.forEach { filename ->
            val target = dir.resolve(filename)
            if (!target.exists() || target.length() == 0L) {
                println("Downloading $filename (tessdata $tessdataVersion)...")
                val url = URI.create("$baseUrl/$filename").toURL()
                val connection = url.openConnection()
                val input = connection.inputStream
                try {
                    Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    input.close()
                }
                println("Downloaded $filename (${target.length()} bytes)")
            } else {
                println("$filename already exists (${target.length()} bytes)")
            }
        }
    }
}

// Include tessdata in the distribution
distributions {
    main {
        contents {
            from(downloadTessdata) {
                into("tessdata")
            }
        }
    }
}

// Ensure tessdata is downloaded before building the distribution
tasks.named("installDist") {
    dependsOn(downloadTessdata)
}

tasks.named("distZip") {
    dependsOn(downloadTessdata)
}

tasks.named("distTar") {
    dependsOn(downloadTessdata)
}
