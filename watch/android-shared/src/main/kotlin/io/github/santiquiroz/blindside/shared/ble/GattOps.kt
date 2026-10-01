package io.github.santiquiroz.blindside.shared.ble

sealed interface GattOp {
    data object DiscoverServices : GattOp
    data class RequestMtu(val mtu: Int) : GattOp
    data object ReadInfo : GattOp
    data object EnableStreamNotify : GattOp
    data class WriteSessionActive(val active: Boolean) : GattOp
    data object ReadRssi : GattOp
    data class WriteCommand(val command: BeltCommand) : GattOp
}

enum class SetupEffect { REPORT_LINK_UP, LOWER_PRIORITY, DISCONNECT }

data class OpQueue(val inFlight: GattOp? = null, val pending: List<GattOp> = emptyList())

data class QueueStep(val queue: OpQueue, val start: GattOp?)

data class CommandResult(val command: BeltCommand, val delivered: Boolean)

const val GATT_SUCCESS_STATUS = 0
const val LOCAL_FAILURE_STATUS = -1
const val MTU_WAIT_MS = 2_000L
const val PAIRING_WAIT_MS = 60_000L
const val GATT_OP_TIMEOUT_MS = 5_000L

// INSUFFICIENT_AUTHENTICATION, INSUFFICIENT_ENCRYPTION and GATT_AUTH_FAIL: the link could not be encrypted with the bond.
val BOND_FAILURE_STATUSES: Set<Int> = setOf(5, 15, 137)

// The role write needs the encrypted link the info read sets up, and goes before the subscription so the belt applies the role's connection parameters before it streams.
// A diagnostic link writes 04 00: the belt keeps a bond's session flag across disconnections, so a stale 1 would block IDENTIFY silently.
fun setupOpsAfterDiscovery(profile: BeltLinkProfile): List<GattOp> = listOf(
    GattOp.RequestMtu(REQUESTED_MTU),
    GattOp.ReadInfo,
    GattOp.WriteCommand(BeltCommand.SetRole(profile.role)),
    GattOp.EnableStreamNotify,
    GattOp.WriteSessionActive(profile.activatesSession),
)

// Spec §5.2: the info read is the first encrypted operation, so it may sit behind the passkey dialog for the whole window.
fun timeoutMsFor(op: GattOp): Long = when (op) {
    is GattOp.RequestMtu -> MTU_WAIT_MS
    GattOp.ReadInfo -> PAIRING_WAIT_MS
    else -> GATT_OP_TIMEOUT_MS
}

fun effectsAfter(op: GattOp, status: Int, profile: BeltLinkProfile = BeltLinkProfile()): List<SetupEffect> = when {
    status != GATT_SUCCESS_STATUS && endsSetupOnFailure(op, status) -> listOf(SetupEffect.DISCONNECT)
    op == GattOp.EnableStreamNotify -> linkUpEffects(profile)
    op == GattOp.WriteSessionActive(true) -> listOf(SetupEffect.LOWER_PRIORITY)
    else -> emptyList()
}

fun isControlWrite(op: GattOp): Boolean = op is GattOp.WriteSessionActive || op is GattOp.WriteCommand

fun commandResultOf(op: GattOp, status: Int): CommandResult? =
    (op as? GattOp.WriteCommand)?.let { CommandResult(it.command, delivered = status == GATT_SUCCESS_STATUS) }

fun enqueue(queue: OpQueue, op: GattOp): QueueStep =
    if (queue.inFlight == null) QueueStep(OpQueue(op, queue.pending), op)
    else QueueStep(queue.copy(pending = queue.pending + op), null)

fun completeInFlight(queue: OpQueue): QueueStep {
    val next = queue.pending.firstOrNull()
    return QueueStep(OpQueue(next, queue.pending.drop(1)), next)
}

fun shouldQueueRssi(queue: OpQueue): Boolean =
    queue.inFlight != GattOp.ReadRssi && GattOp.ReadRssi !in queue.pending

private fun endsSetupOnFailure(op: GattOp, status: Int): Boolean =
    op == GattOp.DiscoverServices || op == GattOp.EnableStreamNotify || isBondFailureOnInfo(op, status)

private fun isBondFailureOnInfo(op: GattOp, status: Int): Boolean = op == GattOp.ReadInfo && status in BOND_FAILURE_STATUSES

// Only 04 01 settles the link, so a link that never activates a session settles once the subscription brings it up.
private fun linkUpEffects(profile: BeltLinkProfile): List<SetupEffect> =
    if (profile.activatesSession) listOf(SetupEffect.REPORT_LINK_UP) else listOf(SetupEffect.REPORT_LINK_UP, SetupEffect.LOWER_PRIORITY)
