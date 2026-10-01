package io.github.santiquiroz.blindside.wear.ble

const val BOND_LOST_AFTER = 2

data class ConnectionAttempt(
    val pairing: Boolean = false,
    val connected: Boolean = false,
    val subscribed: Boolean = false,
    val endedByApp: Boolean = false,
)

enum class AttemptVerdict { SUBSCRIBED, PAIRING_FAILED, BOND_SUSPECT, IGNORED }

data class BondHealth(val consecutiveSuspects: Int = 0) {
    val isLost: Boolean get() = consecutiveSuspects >= BOND_LOST_AFTER
}

// Spec §5.2: with a lost bond every encrypted step fails, so the connection always ends before the CCCD write.
fun attemptVerdict(attempt: ConnectionAttempt): AttemptVerdict = when {
    attempt.subscribed -> AttemptVerdict.SUBSCRIBED
    !attempt.connected || attempt.endedByApp -> AttemptVerdict.IGNORED
    attempt.pairing -> AttemptVerdict.PAIRING_FAILED
    else -> AttemptVerdict.BOND_SUSPECT
}

fun nextBondHealth(health: BondHealth, verdict: AttemptVerdict): BondHealth = when (verdict) {
    AttemptVerdict.SUBSCRIBED -> BondHealth()
    AttemptVerdict.BOND_SUSPECT -> BondHealth(health.consecutiveSuspects + 1)
    AttemptVerdict.PAIRING_FAILED, AttemptVerdict.IGNORED -> health
}
