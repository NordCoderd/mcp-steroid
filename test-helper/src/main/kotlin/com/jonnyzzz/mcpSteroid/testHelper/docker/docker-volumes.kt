/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import java.io.File

data class ContainerVolume(
    val host: File,
    val guest: String,
    val mode: String = "rw",
)


fun ContainerDriver.mapGuestPathToHostPath(path: String): File {
    for (v in volumes) {
        if (v.guest == path) {
            return v.host
        }

        if (path.startsWith(v.guest + "/")) {
            val prefix = path.removePrefix(v.guest + "/").trim('/')
            return v.host.resolve(prefix)
        }
    }
    error("Not found volume for guest path $path")
}

