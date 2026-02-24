/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.ProjectManager

val targetProjects = ProjectManager.getInstance().openProjects
val targetProject = targetProjects.firstOrNull()

if (targetProject != null) {
    val actionManager = ActionManager.getInstance()
    val myAction = actionManager.getAction("YourPlugin.YourAction")  // Replace

    if (myAction != null) {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, targetProject)
            .build()

        val event = AnActionEvent.createEvent(dataContext, myAction.templatePresentation, "mcp", ActionUiKind.NONE, null)
        ActionUtil.performAction(myAction, event)
        println("Action invoked")
    }
}
