package io.github.santiquiroz.blindside.phone.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionCompareTest {

    @Test
    fun `a v-prefixed latest beats a plain current`() {
        assertTrue(VersionCompare.isNewer("v1.3.0", "1.2.0"))
    }

    @Test
    fun `an rc suffix is ignored when comparing`() {
        assertTrue(VersionCompare.isNewer("v1.3.0-rc1", "1.2.0"))
        assertFalse(VersionCompare.isNewer("1.3.0", "1.3.0-rc2"))
    }

    @Test
    fun `minor versions compare numerically so 1-10-0 beats 1-9-9`() {
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.9"))
        assertFalse(VersionCompare.isNewer("1.9.9", "1.10.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(VersionCompare.isNewer("1.3.0", "1.3.0"))
        assertFalse(VersionCompare.isNewer("v1.3.0", "1.3.0"))
        assertFalse(VersionCompare.isNewer("1.2.0", "1.3.0"))
    }

    @Test
    fun `garbage versions are never newer`() {
        assertFalse(VersionCompare.isNewer("abc", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "abc"))
        assertFalse(VersionCompare.isNewer("", ""))
    }
}
