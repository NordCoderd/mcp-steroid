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

val tess4jVersion = "5.17.0"
val tesseractPlatformVersion = "5.5.1-1.5.12"
val leptonicaPlatformVersion = "1.85.0-1.5.12"

dependencies {
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
