package io.github.santiquiroz.blindside.phone.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkSupportTest {
    @Test
    fun `an info with conns comes from the dual-link firmware`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"0.2.0","conns":[]}"""))
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"conns":[{"role":"watch"}]}"""))
    }

    @Test
    fun `version 0_2 or later is dual-link even without conns`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"0.2.0"}"""))
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"1.0.3"}"""))
    }

    @Test
    fun `the MVP firmware serves one link`() {
        assertEquals(LinkSupport.SINGLE_LINK, linkSupportOf("""{"fw":"0.1.0","conn":{"interval_ms":45.0}}"""))
    }

    @Test
    fun `an unreadable info tells nothing`() {
        assertEquals(LinkSupport.UNKNOWN, linkSupportOf("garbage"))
        assertEquals(LinkSupport.UNKNOWN, linkSupportOf("""{"proto":1}"""))
    }

    @Test
    fun `a new info only replaces what was known when it says something`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportAfterInfo(LinkSupport.DUAL_LINK, "garbage"))
        assertEquals(LinkSupport.SINGLE_LINK, linkSupportAfterInfo(LinkSupport.DUAL_LINK, """{"fw":"0.1.0"}"""))
        assertEquals(LinkSupport.UNKNOWN, linkSupportAfterInfo(LinkSupport.UNKNOWN, null))
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(firmwareAtLeast("0.10.0", 0, 2))
        assertFalse(firmwareAtLeast("0.1.9", 0, 2))
        assertFalse(firmwareAtLeast("v2", 0, 2))
        assertFalse(firmwareAtLeast(null, 0, 2))
    }
}
