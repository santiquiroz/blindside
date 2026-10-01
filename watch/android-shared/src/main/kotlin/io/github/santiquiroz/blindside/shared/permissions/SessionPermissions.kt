package io.github.santiquiroz.blindside.shared.permissions

const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
const val PERMISSION_ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

val SESSION_PERMISSIONS: Array<String> = arrayOf(
    PERMISSION_BLUETOOTH_SCAN,
    PERMISSION_BLUETOOTH_CONNECT,
    PERMISSION_ACTIVITY_RECOGNITION,
    PERMISSION_POST_NOTIFICATIONS,
)

sealed interface StartDecision {
    data class Start(val watchSteps: Boolean) : StartDecision
    data object BlockedBluetoothDenied : StartDecision
}

fun startDecision(grants: Map<String, Boolean>): StartDecision =
    if (bluetoothGranted(grants)) {
        StartDecision.Start(watchSteps = grants[PERMISSION_ACTIVITY_RECOGNITION] == true)
    } else {
        StartDecision.BlockedBluetoothDenied
    }

fun bluetoothGranted(grants: Map<String, Boolean>): Boolean =
    grants[PERMISSION_BLUETOOTH_SCAN] == true && grants[PERMISSION_BLUETOOTH_CONNECT] == true

// Steps and notifications are optional and ride along with Bluetooth's prompt; asking for them on every start would add a step.
fun shouldRequestPermissions(grants: Map<String, Boolean>): Boolean = !bluetoothGranted(grants)
