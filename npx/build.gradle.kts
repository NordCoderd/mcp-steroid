import org.gradle.api.tasks.Exec
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

group = "com.jonnyzzz.intellij"
version = rootProject.version

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "npx"
    workingDir = projectDir
    commandLine("npm", "ci")
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "npx"
    workingDir = projectDir
    commandLine("npm", "run", "build")
    dependsOn(npmInstall)
}

val npmBuildTest = tasks.register<Exec>("npmBuildTest") {
    group = "npx"
    workingDir = projectDir
    commandLine("npm", "run", "build:test")
    dependsOn(npmInstall)
}

val npmTest = tasks.register<Exec>("npmTest") {
    group = "npx"
    workingDir = projectDir
    commandLine("npm", "run", "test")
    dependsOn(npmInstall)
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
