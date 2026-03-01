import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val promptGeneratorClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    promptGeneratorClasspath(project(":prompt-generator"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-helper"))
}

val generatedSources = layout.buildDirectory.dir("generated/kotlin/prompts")
val generatedTestSources = layout.buildDirectory.dir("generated/kotlin-test/prompts")
val ijTestOutputDir = rootProject.layout.buildDirectory.dir("generated-ij-tests/prompts")

val generatePrompts by tasks.registering(JavaExec::class) {
    classpath = promptGeneratorClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
    args(
        "--input-dir", layout.projectDirectory.dir("src/main/prompts").asFile.absolutePath,
        "--output-dir", generatedSources.get().asFile.absolutePath,
        "--test-output-dir", generatedTestSources.get().asFile.absolutePath,
        "--ij-test-output-dir", ijTestOutputDir.get().asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("src/main/prompts"))
    outputs.dir(generatedSources)
    outputs.dir(generatedTestSources)
    outputs.dir(ijTestOutputDir)
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedSources)
}

kotlin.sourceSets.test {
    kotlin.srcDir(generatedTestSources)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generatePrompts)
}

tasks.test {
    useJUnitPlatform()
}
