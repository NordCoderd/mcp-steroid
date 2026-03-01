/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

enum class IdeProduct(
    val id: String,
    val displayName: String,
    val jetbrainsProductCode: String,
    val launcherExecutable: String,
) {
    IntelliJIdea(
        id = "idea",
        displayName = "IntelliJ IDEA",
        jetbrainsProductCode = "IIU",
        launcherExecutable = "idea",
    ),
    PyCharm(
        id = "pycharm",
        displayName = "PyCharm",
        jetbrainsProductCode = "PCP",
        launcherExecutable = "pycharm",
    ),
    GoLand(
        id = "goland",
        displayName = "GoLand",
        jetbrainsProductCode = "GO",
        launcherExecutable = "goland",
    ),
    WebStorm(
        id = "webstorm",
        displayName = "WebStorm",
        jetbrainsProductCode = "WS",
        launcherExecutable = "webstorm",
    ),
    Rider(
        id = "rider",
        displayName = "Rider",
        jetbrainsProductCode = "RD",
        launcherExecutable = "rider",
    ),
    CLion(
        id = "clion",
        displayName = "CLion",
        jetbrainsProductCode = "CL",
        launcherExecutable = "clion",
    );

    companion object {
        fun fromString(rawValue: String): IdeProduct = when (rawValue.trim().lowercase()) {
            "idea", "iiu", "intellij", "intellijidea", "intellijideaultimate" -> IntelliJIdea
            "pycharm", "pcp", "python" -> PyCharm
            "goland", "go" -> GoLand
            "webstorm", "ws" -> WebStorm
            "rider", "rd", "dotnet" -> Rider
            "clion", "cl" -> CLion
            else -> error("Unsupported IDE product '$rawValue'. Use one of: idea, pycharm, goland, webstorm, rider, clion.")
        }
    }
}
