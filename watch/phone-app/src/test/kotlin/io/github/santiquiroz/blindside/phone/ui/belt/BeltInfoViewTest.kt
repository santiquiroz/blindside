package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeltInfoViewTest {
    private val mvpInfo = """{"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,""" +
        """"radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"","baud":0}],""" +
        """"imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":0},{"id":1,"who":0,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":3}],""" +
        """"tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}"""

    private val dualInfo = """{"proto":1,"fw":"0.2.0","mtu":247,"radars":[],"imus":[],""" +
        """"conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":5000,"sent":1200,"dropped":0},""" +
        """{"role":"phone","itvl_ms":90.0,"lat":0,"timeout_ms":6000,"sent":1100,"dropped":7}],"bonds":2}"""

    @Test
    fun `the MVP info reads as plain rows`() {
        val expected = listOf(
            InfoRow("Firmware", "0.1.0 · proto 1"),
            InfoRow("Último arranque", "POWERON · 9f3a12c4"),
            InfoRow("Encendido", "42 s"),
            InfoRow("Radar A", "V2.04.23101915 · 256000 baud"),
            InfoRow("Radar B", "no detectado", ok = false),
            InfoRow("IMU A", "WHO 104 · 0 repeticiones"),
            InfoRow("IMU B", "no encontrado", ok = false),
            InfoRow("Potencia", "9 dBm"),
            InfoRow("MTU", "255"),
            InfoRow("Conexión", "45,0 ms · latencia 0 · supervisión 5000 ms"),
        )
        assertEquals(expected, infoRows(parseBeltInfoView(mvpInfo)!!))
    }

    @Test
    fun `both connections and the bond count show once the firmware reports them`() {
        val rows = infoRows(parseBeltInfoView(dualInfo)!!)
        assertTrue(InfoRow("Conexión reloj", "45,0 ms · enviados 1200 · descartados 0") in rows)
        assertTrue(InfoRow("Conexión celular", "90,0 ms · enviados 1100 · descartados 7") in rows)
        assertTrue(InfoRow("Dispositivos emparejados", "2") in rows)
        assertTrue(rows.none { it.label == "Conexión" })
    }

    @Test
    fun `drops on the watch link are a fault and drops on the phone link are not`() {
        val view = parseBeltInfoView(dualInfo)!!.let { it.copy(conns = it.conns.map { conn -> conn.copy(dropped = 3) }) }
        val rows = infoRows(view)
        assertFalse(rows.first { it.label == "Conexión reloj" }.ok)
        assertTrue(rows.first { it.label == "Conexión celular" }.ok)
    }

    @Test
    fun `an mtu below 247 is flagged`() {
        assertFalse(infoRows(parseBeltInfoView("""{"mtu":185}""")!!).first { it.label == "MTU" }.ok)
    }

    @Test
    fun `info that is not a json object has no view`() {
        assertNull(parseBeltInfoView("not json"))
        assertNull(parseBeltInfoView("[]"))
    }

    @Test
    fun `an empty object still names the firmware as unknown`() {
        assertEquals(listOf(InfoRow("Firmware", "desconocido")), infoRows(parseBeltInfoView("{}")!!))
    }

    @Test
    fun `counters read in plain words with faults flagged`() {
        val rows = counterRows(PipelineCounters(packets = 900, lostPackets = 3, malformedPackets = 1, espResets = 1), rssiDbm = -61)
        val expected = listOf(
            InfoRow("Paquetes recibidos", "900"),
            InfoRow("Paquetes perdidos", "3"),
            InfoRow("Paquetes malformados", "1", ok = false),
            InfoRow("Paquetes truncados", "0"),
            InfoRow("Reinicios del cinturón", "1"),
            InfoRow("Señal", "-61 dBm"),
        )
        assertEquals(expected, rows)
        assertEquals(emptyList<InfoRow>(), counterRows(null, -61))
    }

    @Test
    fun `roles read in spanish`() {
        assertEquals("reloj", roleLabel("watch"))
        assertEquals("celular", roleLabel("phone"))
        assertEquals("tablet", roleLabel("tablet"))
    }
}
