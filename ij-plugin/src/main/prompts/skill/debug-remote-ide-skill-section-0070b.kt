/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIdList("")

val matchingActions = allActionIds.filter {
    it.contains("YourKeyword", ignoreCase = true)
}

println("Matching actions (${matchingActions.size}):")
matchingActions.forEach { actionId ->
    val action = actionManager.getAction(actionId)
    println("  - $actionId: ${action?.javaClass?.simpleName}")
}
