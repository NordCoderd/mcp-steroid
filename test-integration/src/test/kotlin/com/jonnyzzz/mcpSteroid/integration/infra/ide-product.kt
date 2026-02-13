/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

enum class IdeProduct(
    val id: String,
    val dockerImageBase: String,
    val launcherExecutable: String,
    val displayName: String,
) {
    IntelliJIdea(
        id = "idea",
        dockerImageBase = "ide-agent",
        launcherExecutable = "idea",
        displayName = "IntelliJ IDEA",
    ),
    PyCharm(
        id = "pycharm",
        dockerImageBase = "pycharm-agent",
        launcherExecutable = "pycharm",
        displayName = "PyCharm",
    );

    companion object {
        fun fromSystemProperty(rawValue: String): IdeProduct = when (rawValue.trim().lowercase()) {
            "idea", "iiu", "intellij", "intellijidea", "intellijideaultimate" -> IntelliJIdea
            "pycharm", "pcp", "python" -> PyCharm
            else -> error("Unsupported test.integration.ide.product='$rawValue'. Use one of: idea, pycharm.")
        }
    }
}
