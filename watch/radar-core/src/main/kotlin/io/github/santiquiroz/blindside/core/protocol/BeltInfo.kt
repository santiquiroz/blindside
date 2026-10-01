package io.github.santiquiroz.blindside.core.protocol

data class ImuScale(val imuId: Int, val gyroLsbPerDps: Double, val accelLsbPerG: Double)

data class BeltInfo(val bootId: String?, val imuScales: List<ImuScale>)

fun parseBeltInfo(json: String): BeltInfo? {
    val root = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val imus = (root["imus"] as? List<*>).orEmpty().mapNotNull { imuScaleOf(it) }
    return BeltInfo(bootId = root["boot_id"] as? String, imuScales = imus)
}

private fun imuScaleOf(entry: Any?): ImuScale? {
    val imu = entry as? Map<*, *> ?: return null
    val id = (imu["id"] as? Double)?.toInt() ?: return null
    val gyro = imu["gyro_lsb_dps"] as? Double ?: GYRO_LSB_PER_DPS
    val accel = imu["accel_lsb_g"] as? Double ?: ACCEL_LSB_PER_G
    return ImuScale(id, gyro, accel)
}
