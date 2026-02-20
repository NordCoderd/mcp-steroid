plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Resolvable configuration to get the plugin .zip from :ij-plugin subproject
val pluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

// Resolvable configuration to get the agent-output-filter executable distribution zip
val agentOutputFilterDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Resolvable configuration to get the NPX package zip from :npx subproject
val npxPackageDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-package"))
    }
}

dependencies {
    pluginZip(project(":ij-plugin"))
    agentOutputFilterDist(project(path = ":agent-output-filter", configuration = "executableDistribution"))
    npxPackageDist(project(":npx"))

    testImplementation(project(":test-helper"))
    testImplementation(project(":agent-output-filter"))
    testImplementation(project(":ai-agents"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    dependsOn(pluginZip, agentOutputFilterDist, npxPackageDist)
    doFirst {
        delete(layout.buildDirectory.dir("test-results/test/binary"))
        val testOutDir = layout.buildDirectory.dir("test-logs/test").get().asFile.also { it.mkdirs() }

        val resolvedPluginZip = pluginZip.singleFile
        require(resolvedPluginZip.isFile) { "Plugin ZIP not found: ${resolvedPluginZip.absolutePath}" }

        systemProperty("test.integration.plugin.zip", resolvedPluginZip.absolutePath)
        systemProperty(
            "test.integration.ide.download.dir",
            layout.buildDirectory.dir("ide-download").get().asFile.absolutePath,
        )
        systemProperty(
            "test.integration.docker",
            layout.projectDirectory.dir("src/test/docker").asFile.absolutePath,
        )
        systemProperty("test.integration.testOutput", testOutDir.absolutePath)
        systemProperty(
            "test.integration.agent.output.filter.zip",
            agentOutputFilterDist.singleFile.absolutePath,
        )
        systemProperty(
            "test.integration.npx.package.zip",
            npxPackageDist.singleFile.absolutePath,
        )
    }
}
