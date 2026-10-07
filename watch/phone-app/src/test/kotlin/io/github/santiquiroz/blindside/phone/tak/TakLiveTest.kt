package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tak.Telemetry
import io.github.santiquiroz.blindside.shared.tak.TelemetryBlip
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Runs only against a real server: BLINDSIDE_TAK_PACKAGE=<package zip with a JDK-readable truststore>.
class TakLiveTest {
    @Test
    fun `publishes a contact and a marker and hears a teammate on a real server`() = runBlocking {
        val path = System.getenv("BLINDSIDE_TAK_PACKAGE")
        assumeTrue(path != null, "BLINDSIDE_TAK_PACKAGE not set")
        val pkg = (readTakPackage(File(path!!).readBytes()) as PackageResult.Ok).pkg
        val ids = TakIds(deviceIdOf(pkg.clientName), "Live")
        val link = TakLink(tlsConnector(pkg, sslContextOf(pkg)), ids, "live-test")
        var roster = TeamRoster()
        val job = launch { link.run() }
        val listener = launch { link.incoming.collect { roster = roster.with(it, ids.callsign, System.currentTimeMillis()) } }
        withTimeout(15_000) { link.status.first { it is LinkStatus.Connected } }

        val here = GeoPoint(5.0689, -75.5174)
        val telemetry = Telemetry(true, listOf(TelemetryBlip(1, 45.0, 5.0, Confidence.BOTH)), mapOf(TacticalKind.BASE to here))
        repeat(8) {
            val out = publishTelemetry(PublishState(), telemetry, GeoFix(here, 4.0), 500, ids, true, System.currentTimeMillis())
            out.events.forEach(link::send)
            delay(1_000)
        }
        val mates = roster.mates(System.currentTimeMillis())
        listener.cancel()
        job.cancel()
        println("mates heard: ${mates.map { it.callsign }}")
        assertTrue(mates.isNotEmpty(), "no teammate heard; run tak/tak-probe.py with --at while this test runs")
    }
}
