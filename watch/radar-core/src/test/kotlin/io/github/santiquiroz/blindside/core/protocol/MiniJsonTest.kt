package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MiniJsonTest {
    @Test
    fun `objects, arrays, numbers, literals and nesting parse`() {
        val parsed = MiniJson.parse("""{ "a": [1, -2.5, 3e2], "b": {"c": true, "d": null}, "e": false }""")

        assertEquals(mapOf("a" to listOf(1.0, -2.5, 300.0), "b" to mapOf("c" to true, "d" to null), "e" to false), parsed)
    }

    @Test
    fun `strings keep escapes and unicode`() {
        assertEquals("a\"b\\c/d\neé", MiniJson.parse("\"a\\\"b\\\\c\\/d\\ne\\u00e9\""))
    }

    @Test
    fun `empty containers parse`() {
        assertEquals(mapOf<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(listOf<Any?>(), MiniJson.parse("[ ]"))
    }

    @Test
    fun `malformed text is rejected and parseOrNull returns null`() {
        assertThrows<IllegalArgumentException> { MiniJson.parse("""{"a":1""") }
        assertThrows<IllegalArgumentException> { MiniJson.parse("""{"a":1} x""") }
        assertNull(MiniJson.parseOrNull("not json"))
    }

    @Test
    fun `written objects read back`() {
        val text = MiniJson.obj(listOf("name" to MiniJson.quote("say \"hi\"\n"), "list" to MiniJson.array(listOf("1", "2.5"))))

        assertEquals(mapOf("name" to "say \"hi\"\n", "list" to listOf(1.0, 2.5)), MiniJson.parse(text))
    }
}
