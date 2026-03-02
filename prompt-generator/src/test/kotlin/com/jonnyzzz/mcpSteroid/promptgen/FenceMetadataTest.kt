/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.promptgen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FenceMetadataTest {

    @Test
    fun `default metadata`() {
        assertTrue(FenceMetadata.DEFAULT.isDefault)
        assertEquals("", FenceMetadata.DEFAULT.toFenceSuffix())
    }

    @Test
    fun `parse empty string returns default`() {
        assertEquals(FenceMetadata.DEFAULT, FenceMetadata.parse(""))
    }

    @Test
    fun `parse single product code`() {
        val meta = FenceMetadata.parse("RD")
        assertEquals(setOf("RD"), meta.productCodes)
        assertNull(meta.minVersion)
        assertNull(meta.maxVersion)
    }

    @Test
    fun `parse multiple product codes`() {
        val meta = FenceMetadata.parse("IU,RD")
        assertEquals(setOf("IU", "RD"), meta.productCodes)
    }

    @Test
    fun `parse product code with min version`() {
        val meta = FenceMetadata.parse("RD;>=254")
        assertEquals(setOf("RD"), meta.productCodes)
        assertEquals(254, meta.minVersion)
        assertNull(meta.maxVersion)
    }

    @Test
    fun `parse product code with max version`() {
        val meta = FenceMetadata.parse("RD;<=261")
        assertEquals(setOf("RD"), meta.productCodes)
        assertNull(meta.minVersion)
        assertEquals(261, meta.maxVersion)
    }

    @Test
    fun `parse product code with both version bounds`() {
        val meta = FenceMetadata.parse("IU,RD;>=253,<=261")
        assertEquals(setOf("IU", "RD"), meta.productCodes)
        assertEquals(253, meta.minVersion)
        assertEquals(261, meta.maxVersion)
    }

    @Test
    fun `parse version only no product codes`() {
        val meta = FenceMetadata.parse(";>=253")
        assertTrue(meta.productCodes.isEmpty())
        assertEquals(253, meta.minVersion)
    }

    @Test
    fun `unknown product code throws`() {
        assertThrows<IllegalArgumentException> {
            FenceMetadata.parse("XX")
        }
    }

    @Test
    fun `invalid version constraint throws`() {
        assertThrows<IllegalStateException> {
            FenceMetadata.parse("RD;>253")
        }
    }

    @Test
    fun `toFenceSuffix single product`() {
        val meta = FenceMetadata(setOf("RD"), null, null)
        assertEquals("[RD]", meta.toFenceSuffix())
    }

    @Test
    fun `toFenceSuffix multiple products sorted`() {
        val meta = FenceMetadata(setOf("RD", "IU"), null, null)
        assertEquals("[IU,RD]", meta.toFenceSuffix())
    }

    @Test
    fun `toFenceSuffix product with version`() {
        val meta = FenceMetadata(setOf("RD"), 254, null)
        assertEquals("[RD;>=254]", meta.toFenceSuffix())
    }

    @Test
    fun `toFenceSuffix product with both versions`() {
        val meta = FenceMetadata(setOf("IU", "RD"), 253, 261)
        assertEquals("[IU,RD;>=253,<=261]", meta.toFenceSuffix())
    }

    @Test
    fun `round-trip parse then toFenceSuffix`() {
        val inputs = listOf("RD", "IU,RD", "RD;>=254", "IU,RD;>=253,<=261")
        for (input in inputs) {
            val meta = FenceMetadata.parse(input)
            val suffix = meta.toFenceSuffix()
            val reparsed = FenceMetadata.parse(suffix.removeSurrounding("[", "]"))
            assertEquals(meta, reparsed, "Round-trip failed for input: $input")
        }
    }

    @Test
    fun `extractFenceBracket with annotation`() {
        assertEquals("RD", extractFenceBracket("```kotlin[RD]"))
        assertEquals("IU,RD;>=253", extractFenceBracket("```kotlin[IU,RD;>=253]"))
        assertEquals("RD", extractFenceBracket("  ```kotlin[RD]"))
    }

    @Test
    fun `extractFenceBracket without annotation`() {
        assertNull(extractFenceBracket("```kotlin"))
        assertNull(extractFenceBracket("```kotlin "))
        assertNull(extractFenceBracket("```java[RD]"))
    }

    @Test
    fun `all valid product codes accepted`() {
        for (code in listOf("IU", "RD", "CL", "GO", "PY", "WS", "RM", "DB")) {
            val meta = FenceMetadata.parse(code)
            assertEquals(setOf(code), meta.productCodes)
        }
    }
}
