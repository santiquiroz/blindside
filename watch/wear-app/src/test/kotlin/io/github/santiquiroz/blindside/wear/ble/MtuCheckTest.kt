package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MtuCheckTest {
    @Test
    fun `an mtu of 247 or more streams`() {
        assertEquals(MtuAction.OK, mtuAction(255, retried = false))
        assertEquals(MtuAction.OK, mtuAction(247, retried = true))
    }

    @Test
    fun `a low mtu reconnects once and then fails`() {
        assertEquals(MtuAction.RETRY_ONCE, mtuAction(185, retried = false))
        assertEquals(MtuAction.FAIL, mtuAction(185, retried = true))
    }

    @Test
    fun `an unknown mtu is not judged`() {
        assertEquals(MtuAction.OK, mtuAction(null, retried = false))
    }

    @Test
    fun `the smaller of the two mtu sources wins`() {
        assertEquals(185, effectiveMtu(callbackMtu = 255, infoMtu = 185))
        assertEquals(255, effectiveMtu(callbackMtu = null, infoMtu = 255))
        assertNull(effectiveMtu(callbackMtu = null, infoMtu = null))
    }

    @Test
    fun `the mtu field is read from the info json`() {
        assertEquals(255, mtuFromInfo("""{"proto":1,"fw":"0.1.0","mtu":255,"radars":[]}"""))
        assertNull(mtuFromInfo("""{"proto":1}"""))
    }
}
