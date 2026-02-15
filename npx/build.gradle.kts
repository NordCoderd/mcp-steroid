import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
    id("com.github.node-gradle.node")
}

node {
    download.set(true)
    version.set("20.20.0")
}

val patchPackageVersion = tasks.register("patchPackageVersion") {
    group = "npx"
    description = "Set package.json version from Gradle project.version"
    dependsOn(tasks.npmInstall)

    val packageJsonFile = projectDir.resolve("package.json")
    inputs.property("projectVersion", project.version.toString())
    inputs.file(packageJsonFile)
    outputs.file(packageJsonFile)

    doLast {
        val content = packageJsonFile.readText()
        val updated = content.replace(
            Regex(""""version"\s*:\s*"[^"]*""""),
            """"version": "${project.version}""""
        )
        packageJsonFile.writeText(updated)
    }
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    group = "npx"
    npmCommand.set(listOf("run", "build"))
    dependsOn(patchPackageVersion)
}

val npmBuildTest = tasks.register<NpmTask>("npmBuildTest") {
    group = "npx"
    npmCommand.set(listOf("run", "build:test"))
    dependsOn(patchPackageVersion)
}

val npmTest = tasks.register<NpmTask>("npmTest") {
    group = "npx"
    npmCommand.set(listOf("run", "test"))
    dependsOn(tasks.npmInstall)
}

val npxPackageZip = tasks.register<Zip>("npxPackageZip") {
    group = "npx"
    description = "Build distributable NPX package for integration tests"
    dependsOn(npmBuild)

    archiveBaseName.set("mcp-steroid-npx")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(projectDir) {
        include("package.json")
        include("package-lock.json")
    }
    from(projectDir.resolve("dist")) {
        into("dist")
    }
}

val npxPackageElements = configurations.create("npxPackageElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-package"))
    }
}

artifacts {
    add(npxPackageElements.name, npxPackageZip)
}

tasks.named("assemble") {
    dependsOn(npxPackageZip)
}

tasks.named("check") {
    dependsOn(npmTest)
}
