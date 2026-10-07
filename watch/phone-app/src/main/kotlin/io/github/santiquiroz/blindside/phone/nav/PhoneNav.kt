package io.github.santiquiroz.blindside.phone.nav

enum class PhoneTab { RADAR, RECORDINGS, VIEWER, BELT, TEAM }

data class PhoneNav(val tab: PhoneTab = PhoneTab.RADAR, val viewing: String? = null)

fun selectTab(nav: PhoneNav, tab: PhoneTab): PhoneNav = nav.copy(tab = tab)

fun openRecording(nav: PhoneNav, name: String): PhoneNav = nav.copy(tab = PhoneTab.VIEWER, viewing = name)

fun afterDelete(nav: PhoneNav, name: String): PhoneNav = if (nav.viewing == name) nav.copy(viewing = null) else nav

// Back walks to the radar first, so one more back from the radar leaves the app.
fun backFrom(nav: PhoneNav): PhoneNav? = if (nav.tab == PhoneTab.RADAR) null else nav.copy(tab = PhoneTab.RADAR)

fun tabLabel(tab: PhoneTab): String = when (tab) {
    PhoneTab.RADAR -> "Radar"
    PhoneTab.RECORDINGS -> "Grabaciones"
    PhoneTab.VIEWER -> "Visor"
    PhoneTab.BELT -> "Cinturón"
    PhoneTab.TEAM -> "Equipo"
}

fun navToStrings(nav: PhoneNav): List<String> = listOfNotNull(nav.tab.name, nav.viewing)

fun navFromStrings(values: List<String>): PhoneNav =
    PhoneNav(PhoneTab.entries.firstOrNull { it.name == values.getOrNull(0) } ?: PhoneTab.RADAR, values.getOrNull(1))

// Only the radar and belt tabs show the live scene and its counters; the others must not keep the pipeline ticking.
fun sceneWanted(tab: PhoneTab): Boolean = tab == PhoneTab.RADAR || tab == PhoneTab.BELT
