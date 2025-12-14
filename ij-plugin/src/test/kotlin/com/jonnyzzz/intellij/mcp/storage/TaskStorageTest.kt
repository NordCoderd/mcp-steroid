/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.intellij.mcp.storage

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for task tracking and feedback storage in ExecutionStorage.
 */
class TaskStorageTest : BasePlatformTestCase() {

    private lateinit var storage: ExecutionStorage

    override fun setUp() {
        super.setUp()
        storage = project.service<ExecutionStorage>()
    }

    fun testAddExecutionToNewTask() {
        val taskId = "test-task-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        // Create execution first
        storage.createExecution(executionId, "println(1)", ExecutionParams())

        // Add to task
        storage.addExecutionToTask(taskId, executionId, project.name)

        // Verify task was created
        val task = storage.readTask(taskId)
        assertNotNull("Task should be created", task)
        assertEquals(taskId, task!!.taskId)
        assertEquals(project.name, task.projectName)
        assertTrue("Task should contain execution", task.executionIds.contains(executionId))
        assertNull("Task should not have feedback yet", task.feedback)
    }

    fun testAddMultipleExecutionsToTask() {
        val taskId = "test-task-multi-${System.currentTimeMillis()}"
        val executionId1 = storage.generateExecutionId("println(1)", ExecutionParams())
        val executionId2 = storage.generateExecutionId("println(2)", ExecutionParams())

        // Create executions
        storage.createExecution(executionId1, "println(1)", ExecutionParams())
        storage.createExecution(executionId2, "println(2)", ExecutionParams())

        // Add to same task
        storage.addExecutionToTask(taskId, executionId1, project.name)
        storage.addExecutionToTask(taskId, executionId2, project.name)

        // Verify task has both executions
        val task = storage.readTask(taskId)
        assertNotNull("Task should exist", task)
        assertEquals(2, task!!.executionIds.size)
        assertTrue("Task should contain first execution", task.executionIds.contains(executionId1))
        assertTrue("Task should contain second execution", task.executionIds.contains(executionId2))
    }

    fun testGetTaskIdForExecution() {
        val taskId = "test-task-lookup-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        val retrievedTaskId = storage.getTaskIdForExecution(executionId)
        assertEquals("Should retrieve correct task ID", taskId, retrievedTaskId)
    }

    fun testSaveFeedback() {
        val taskId = "test-task-feedback-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        val feedback = ExecutionFeedback(
            taskId = taskId,
            executionId = executionId,
            successRating = 0.85,
            explanation = "Execution completed successfully with expected output"
        )

        storage.saveFeedback(feedback)

        // Verify feedback in task
        val task = storage.readTask(taskId)
        assertNotNull("Task should exist", task)
        assertNotNull("Task should have feedback", task!!.feedback)
        assertEquals(0.85, task.feedback!!.successRating, 0.001)
        assertEquals("Execution completed successfully with expected output", task.feedback!!.explanation)

        // Verify feedback in execution directory
        val execFeedback = storage.readExecutionFeedback(executionId)
        assertNotNull("Execution should have feedback", execFeedback)
        assertEquals(taskId, execFeedback!!.taskId)
        assertEquals(0.85, execFeedback.successRating, 0.001)
    }

    fun testFeedbackOverwritesPrevious() {
        val taskId = "test-task-overwrite-${System.currentTimeMillis()}"
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())

        storage.createExecution(executionId, "println(1)", ExecutionParams())
        storage.addExecutionToTask(taskId, executionId, project.name)

        // Save initial feedback
        storage.saveFeedback(ExecutionFeedback(
            taskId = taskId,
            executionId = executionId,
            successRating = 0.3,
            explanation = "First attempt failed"
        ))

        // Save updated feedback
        storage.saveFeedback(ExecutionFeedback(
            taskId = taskId,
            executionId = executionId,
            successRating = 0.9,
            explanation = "Retry succeeded"
        ))

        // Verify latest feedback is saved
        val task = storage.readTask(taskId)
        assertNotNull(task?.feedback)
        assertEquals(0.9, task!!.feedback!!.successRating, 0.001)
        assertEquals("Retry succeeded", task.feedback!!.explanation)
    }

    fun testListTasks() {
        val taskId1 = "test-task-list-1-${System.currentTimeMillis()}"
        val taskId2 = "test-task-list-2-${System.currentTimeMillis()}"
        val executionId1 = storage.generateExecutionId("println(1)", ExecutionParams())
        val executionId2 = storage.generateExecutionId("println(2)", ExecutionParams())

        storage.createExecution(executionId1, "println(1)", ExecutionParams())
        storage.createExecution(executionId2, "println(2)", ExecutionParams())
        storage.addExecutionToTask(taskId1, executionId1, project.name)
        storage.addExecutionToTask(taskId2, executionId2, project.name)

        val tasks = storage.listTasks()
        assertTrue("Should have at least 2 tasks", tasks.size >= 2)
        assertTrue("Should contain task 1", tasks.any { it.taskId == taskId1 })
        assertTrue("Should contain task 2", tasks.any { it.taskId == taskId2 })
    }

    fun testReadNonExistentTask() {
        val task = storage.readTask("non-existent-task-id")
        assertNull("Reading non-existent task should return null", task)
    }

    fun testReadFeedbackForNonExistentTask() {
        val feedback = storage.readFeedback("non-existent-task-id")
        assertNull("Reading feedback for non-existent task should return null", feedback)
    }

    fun testGetTaskIdForExecutionWithoutTask() {
        val executionId = storage.generateExecutionId("println(1)", ExecutionParams())
        storage.createExecution(executionId, "println(1)", ExecutionParams())

        val taskId = storage.getTaskIdForExecution(executionId)
        assertNull("Execution without task should return null task ID", taskId)
    }
}
