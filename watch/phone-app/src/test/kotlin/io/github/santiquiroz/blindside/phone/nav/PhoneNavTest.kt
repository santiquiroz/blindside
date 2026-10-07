package io.github.santiquiroz.blindside.phone.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhoneNavTest {
    @Test
    fun `the five destinations follow the spec order with their names`() {
        assertEquals(listOf("Radar", "Grabaciones", "Visor", "Cinturón", "Equipo"), PhoneTab.entries.map(::tabLabel))
    }

    @Test
    fun `the app opens on the radar with nothing to view`() {
        assertEquals(PhoneNav(PhoneTab.RADAR, viewing = null), PhoneNav())
    }

    @Test
    fun `opening a recording jumps to the viewer with it`() {
        assertEquals(PhoneNav(PhoneTab.VIEWER, "a.bsrec"), openRecording(PhoneNav(PhoneTab.RECORDINGS), "a.bsrec"))
    }

    @Test
    fun `switching tabs keeps the recording in the viewer`() {
        assertEquals(PhoneNav(PhoneTab.BELT, "a.bsrec"), selectTab(PhoneNav(PhoneTab.VIEWER, "a.bsrec"), PhoneTab.BELT))
    }

    @Test
    fun `deleting the open recording empties the viewer and other deletions leave it`() {
        val viewing = PhoneNav(PhoneTab.RECORDINGS, "a.bsrec")
        assertEquals(PhoneNav(PhoneTab.RECORDINGS, null), afterDelete(viewing, "a.bsrec"))
        assertEquals(viewing, afterDelete(viewing, "b.bsrec"))
    }

    @Test
    fun `back returns to the radar and then leaves the app`() {
        assertEquals(PhoneNav(PhoneTab.RADAR, "a.bsrec"), backFrom(PhoneNav(PhoneTab.VIEWER, "a.bsrec")))
        assertNull(backFrom(PhoneNav(PhoneTab.RADAR)))
    }

    @Test
    fun `navigation survives being saved and a broken save falls back to the radar`() {
        val nav = PhoneNav(PhoneTab.VIEWER, "a.bsrec")
        assertEquals(nav, navFromStrings(navToStrings(nav)))
        assertEquals(PhoneNav(PhoneTab.BELT), navFromStrings(navToStrings(PhoneNav(PhoneTab.BELT))))
        assertEquals(PhoneNav(), navFromStrings(listOf("PARTY")))
        assertEquals(PhoneNav(), navFromStrings(emptyList()))
    }

    @Test
    fun `only the radar and belt tabs keep live scenes coming`() {
        assertEquals(listOf(true, false, false, true, false), PhoneTab.entries.map(::sceneWanted))
    }
}
