/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UtilsTest {

    @Test
    fun `toPromptClassName simple name`() {
        assertEquals("Hello", "hello".toPromptClassName())
    }

    @Test
    fun `toPromptClassName hyphenated`() {
        assertEquals("RunConfiguration", "run-configuration".toPromptClassName())
    }

    @Test
    fun `toPromptClassName underscored`() {
        assertEquals("MyClassName", "my_class_name".toPromptClassName())
    }

    @Test
    fun `toPromptClassName dotted`() {
        assertEquals("FileName", "file.name".toPromptClassName())
    }

    @Test
    fun `toPromptClassName all-caps segment lowercased`() {
        assertEquals("Api", "API".toPromptClassName())
    }

    @Test
    fun `toPromptClassName all-caps segments in compound name`() {
        assertEquals("ApiClient", "API-client".toPromptClassName())
    }

    @Test
    fun `toPromptClassName intellij special case`() {
        assertEquals("IntelliJ", "intellij".toPromptClassName())
    }

    @Test
    fun `toPromptClassName intellij case-insensitive`() {
        assertEquals("IntelliJ", "INTELLIJ".toPromptClassName())
    }

    @Test
    fun `toPromptClassName mixed separators`() {
        assertEquals("MyCoolThing", "my-cool_thing".toPromptClassName())
    }

    @Test
    fun `toPromptClassName mixed separators with dot`() {
        assertEquals("MyCoolThingKt", "my-cool_thing.kt".toPromptClassName())
    }

    @Test
    fun `toPromptClassName single char`() {
        assertEquals("A", "a".toPromptClassName())
    }

    @Test
    fun `toPromptIdentifierName lowercases first char`() {
        assertEquals("runConfiguration", "run-configuration".toPromptIdentifierName())
    }

    @Test
    fun `toPromptIdentifierName simple`() {
        assertEquals("hello", "hello".toPromptIdentifierName())
    }

    @Test
    fun `toPromptIdentifierName intellij`() {
        assertEquals("intelliJ", "intellij".toPromptIdentifierName())
    }

    @Test
    fun `titleCase capitalizes first char`() {
        assertEquals("Hello", "hello".titleCase())
    }

    @Test
    fun `titleCase already capitalized`() {
        assertEquals("Hello", "Hello".titleCase())
    }

    @Test
    fun `titleCase single char`() {
        assertEquals("A", "a".titleCase())
    }

    @Test
    fun `titleCase empty string`() {
        assertEquals("", "".titleCase())
    }
}
