package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BeltInfoTest {
    private val contractExample = """
        {"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,
         "radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],
         "imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":0},{"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":3}],
         "tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}
    """.trimIndent()

    @Test
    fun `the contract example yields the boot id and both imu scales`() {
        val info = parseBeltInfo(contractExample)!!

        assertEquals("9f3a12c4", info.bootId)
        assertEquals(listOf(ImuScale(0, 65.5, 4096.0), ImuScale(1, 65.5, 4096.0)), info.imuScales)
    }

    @Test
    fun `missing scales fall back to the protocol defaults`() {
        val info = parseBeltInfo("""{"boot_id":"01","imus":[{"id":1,"gyro_lsb_dps":32.8}]}""")!!

        assertEquals(listOf(ImuScale(1, 32.8, ACCEL_LSB_PER_G)), info.imuScales)
    }

    @Test
    fun `info without a boot id or imus still parses`() {
        assertEquals(BeltInfo(null, emptyList()), parseBeltInfo("""{"proto":1}"""))
    }

    @Test
    fun `text that is not a json object is ignored`() {
        assertNull(parseBeltInfo("garbage"))
        assertNull(parseBeltInfo("[1,2]"))
    }
}
