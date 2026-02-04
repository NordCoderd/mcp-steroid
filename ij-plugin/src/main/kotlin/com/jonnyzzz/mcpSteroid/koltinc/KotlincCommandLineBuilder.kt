/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.execution.CommandLineWrapperUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class KotlincCommandLine(
    val args: List<String>,
    val outputJar: Path,
) {
    companion object
}

fun KotlincCommandLine.Companion.builder(outputJar: Path) = KotlincCommandLineBuilder(outputJar)

class KotlincCommandLineBuilder(
    private val outputJar: Path,
) {
    private val sources = mutableListOf<Path>()
    private val classpath = linkedSetOf<Path>()
    private var classpathArgFile: Path? = null
    private var jvmTarget: String = DEFAULT_JVM_TARGET
    private var noStdLib: Boolean = false

    fun addSource(source: Path) = apply {
        require(Files.exists(source)) { "Source file does not exist: $source" }
        sources.add(source)
    }

    fun addClasspathEntries(classpath: Collection<Path>) = apply {
        classpath.forEach { addClasspathEntry(it) }
    }

    fun addClasspathEntry(entry: Path) = apply {
        require(Files.exists(entry)) { "Classpath entry does not exist: $entry" }
        require(Files.isDirectory(entry) || Files.isRegularFile(entry)) {
            "Classpath entry must be a directory or file: $entry"
        }
        classpath.add(entry)
    }

    fun withClasspathArgFile(path: Path) = apply {
        classpathArgFile = path
    }

    fun withJvmTarget(target: String) = apply {
        require(target.isNotBlank()) { "JVM target must not be blank" }
        jvmTarget = target
    }

    fun withNoStdLib(enabled: Boolean) = apply {
        noStdLib = enabled
    }

    fun build(): KotlincCommandLine {
        require(sources.isNotEmpty()) { "No Kotlin source files provided" }
        outputJar.parent?.let { Files.createDirectories(it) }

        val args = mutableListOf<String>()

        if (classpath.isNotEmpty()) {
            val cp = classpath.joinToString(File.pathSeparator) { it.toString() }
            val argFile = classpathArgFile
            if (argFile != null) {
                argFile.parent?.let { Files.createDirectories(it) }
                CommandLineWrapperUtil.writeArgumentsFile(
                    argFile.toFile(),
                    listOf("-classpath", cp),
                    StandardCharsets.UTF_8,
                )
                args.add("@${argFile.toAbsolutePath()}")
            } else {
                args.add("-classpath")
                args.add(cp)
            }
        }

        args.add("-jvm-target")
        args.add(jvmTarget)

        if (noStdLib) {
            args.add("-no-stdlib")
        }

        args.add("-d")
        args.add(outputJar.toString())

        for (source in sources) {
            args.add(source.toString())
        }

        return KotlincCommandLine(args = args, outputJar = outputJar)
    }

    companion object {
        const val DEFAULT_JVM_TARGET = "21"
    }
}
