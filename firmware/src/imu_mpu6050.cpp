#include "imu_mpu6050.h"

#include "blindside_config.h"
#include "mpu6050_registers.h"

namespace {

struct Probe {
    uint8_t address;
    uint8_t who_am_i;
};

bool write_register(TwoWire& wire, uint8_t address, uint8_t reg, uint8_t value) {
    wire.beginTransmission(address);
    wire.write(reg);
    wire.write(value);
    return wire.endTransmission() == 0;
}

bool read_registers(TwoWire& wire, uint8_t address, uint8_t reg, uint8_t* out, uint8_t length) {
    wire.beginTransmission(address);
    wire.write(reg);
    if (wire.endTransmission(false) != 0) {
        return false;
    }
    if (wire.requestFrom(address, length) != length) {
        return false;
    }
    for (uint8_t i = 0; i < length; ++i) {
        out[i] = static_cast<uint8_t>(wire.read());
    }
    return true;
}

uint8_t read_who_am_i(TwoWire& wire, uint8_t address) {
    uint8_t who_am_i = 0;
    return read_registers(wire, address, kMpuRegWhoAmI, &who_am_i, 1) ? who_am_i : 0;
}

Probe probe(TwoWire& wire) {
    const uint8_t candidates[] = {kMpuDefaultAddress, kMpuAlternateAddress};
    for (uint8_t address : candidates) {
        uint8_t who_am_i = read_who_am_i(wire, address);
        if (who_am_i_is_usable(who_am_i)) {
            return Probe{address, who_am_i};
        }
    }
    return Probe{0, 0};
}

bool apply_config(TwoWire& wire, uint8_t address, uint8_t who_am_i) {
    MpuConfigPlan plan = mpu_config_plan(who_am_i);
    for (uint8_t i = 0; i < plan.count; ++i) {
        if (!write_register(wire, address, plan.writes[i].reg, plan.writes[i].value)) {
            return false;
        }
    }
    return true;
}

void start_bus(const ImuDevice& device) {
    device.wire->begin(device.sda_pin, device.scl_pin, config::kI2cFrequencyHz);
    device.wire->setTimeOut(config::kI2cTimeoutMs);
}

bool initialize(ImuDevice& device) {
    Probe found = probe(*device.wire);
    device.address = found.address;
    if (found.address == 0) {
        return false;
    }
    device.who_am_i = found.who_am_i;
    return apply_config(*device.wire, found.address, found.who_am_i);
}

}  // namespace

ImuDevice imu_create(uint8_t imu_id, TwoWire* wire, int sda_pin, int scl_pin) {
    ImuDevice device{};
    device.imu_id = imu_id;
    device.wire = wire;
    device.sda_pin = sda_pin;
    device.scl_pin = scl_pin;
    return device;
}

bool imu_begin(ImuDevice& device) {
    start_bus(device);
    return initialize(device);
}

bool imu_recover(ImuDevice& device) {
    device.wire->end();
    return imu_begin(device);
}

ImuRead imu_read(const ImuDevice& device) {
    ImuRead result{};
    uint8_t burst[kMpuBurstSize];
    if (device.address == 0) {
        return result;
    }
    result.ok = read_registers(*device.wire, device.address, kMpuRegAccelXoutH, burst, kMpuBurstSize);
    if (result.ok) {
        result.reading = reading_from_burst(burst);
    }
    return result;
}
