#pragma once

#include <stddef.h>
#include <stdint.h>

namespace config {

constexpr char kFirmwareVersion[] = "0.1.0";
constexpr uint32_t kSerialBaud = 115200;
constexpr size_t kSerialTxBufferBytes = 1024;

constexpr int8_t kRadarARxPin = 16;
constexpr int8_t kRadarATxPin = 17;
// UART1's default pins (9/10) belong to the SPI flash, so radar B goes through the GPIO matrix.
constexpr int8_t kRadarBRxPin = 26;
constexpr int8_t kRadarBTxPin = 27;
constexpr size_t kRadarRxBufferBytes = 2048;
constexpr uint32_t kRadarAckTimeoutMs = 300;
constexpr uint32_t kRadarRestartGapMs = 100;
constexpr uint32_t kRadarReadTimeoutMs = 20;

constexpr int kImuASdaPin = 32;
constexpr int kImuASclPin = 33;
constexpr int kImuBSdaPin = 21;
constexpr int kImuBSclPin = 22;
constexpr uint32_t kI2cFrequencyHz = 100000;
constexpr uint16_t kI2cTimeoutMs = 4;
// IMU ticks sit this far behind the task's wakes, so FreeRTOS tick jitter never makes a tick look skipped.
constexpr uint32_t kImuGridLagMs = 2;

constexpr int kStatusLedPin = 2;
constexpr int kBootButtonPin = 0;

constexpr uint32_t kInfoRefreshMs = 1000;
constexpr uint32_t kDiagnosticsPeriodMs = 5000;
constexpr uint32_t kLoopPeriodMs = 5;

constexpr int kSensorCore = 1;
constexpr uint32_t kRadarTaskPriority = 6;
constexpr uint32_t kImuTaskPriority = 5;
constexpr uint32_t kBundlerTaskPriority = 3;
constexpr uint32_t kTaskStackBytes = 6144;
constexpr size_t kRadarQueueDepth = 32;
constexpr size_t kImuQueueDepth = 32;

constexpr char kServiceUuid[] = "569f3867-024f-4498-a979-90a762ad3593";
constexpr char kStreamUuid[] = "37869398-ecc2-4915-90a1-13d39d708ad5";
constexpr char kInfoUuid[] = "278b9369-d8ac-4eda-868b-7bfd0dea5dc6";
constexpr char kControlUuid[] = "725c9a6e-0c7b-45d2-bef6-48c03be7c092";

// BLE units: connection interval 1.25 ms, supervision timeout 10 ms, advertising interval 0.625 ms.
constexpr uint16_t kConnIntervalMinUnits = 24;
constexpr uint16_t kConnIntervalMaxUnits = 40;
constexpr uint16_t kConnLatency = 0;
constexpr uint16_t kSupervisionTimeoutUnits = 400;
constexpr uint16_t kDataLengthOctets = 251;
constexpr uint16_t kPreferredMtu = 255;
constexpr int8_t kBleTxPowerDbm = 9;
constexpr uint16_t kFastAdvertisingMinUnits = 32;
constexpr uint16_t kFastAdvertisingMaxUnits = 80;
constexpr uint16_t kSlowAdvertisingMinUnits = 160;
constexpr uint16_t kSlowAdvertisingMaxUnits = 320;
// The classic ESP32 resolves peer RPAs in the host, so the controller filter may never match the watch (spike S10).
constexpr bool kConnectWhitelistOnly = false;
// Spike S12: false switches to plan B (LE SC Just Works inside the window, `control` relaxed to WRITE_ENC).
constexpr bool kRequireMitm = true;

constexpr char kPreferencesNamespace[] = "blindside";
constexpr char kPasskeyKey[] = "passkey";

}  // namespace config
