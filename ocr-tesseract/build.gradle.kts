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
val tessdataDownloadDir = layout.buildDirectory.dir("tessdata-download")
val tessdataDir = layout.buildDirectory.dir("tessdata-data")

val tessdataUrls = listOf(
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/eng.traineddata",
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/osd.traineddata",
)

// Download tessdata files
val downloadTessdata by tasks.registering {
    inputs.property("downloads", tessdataUrls.joinToString(","))
    outputs.dir(tessdataDownloadDir)

    doFirst {
        delete(tessdataDownloadDir)
        mkdir(tessdataDownloadDir)
        download {
            for (url in tessdataUrls) {
                run {
                    src(url)
                    dest(tessdataDownloadDir.map { it.file(url.substringAfterLast("/")) })
                    onlyIfModified(true)
                }
            }
        }
    }
}

val syncTessdata by tasks.registering(Sync::class) {
    description = "Sync tessdata files to distribution directory"
    dependsOn(downloadTessdata)

    from(tessdataDownloadDir)
    into(tessdataDir)
}

// Include tessdata in the distribution
distributions {
    main {
        contents {
            //dependeny to the task behind   tessdataDownloadDir
            from(syncTessdata) {
                into("tessdata")
            }
        }
    }
}
