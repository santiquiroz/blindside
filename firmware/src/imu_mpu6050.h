#pragma once

#include <Arduino.h>
#include <Wire.h>

#include "imu_accumulator.h"

struct ImuDevice {
    uint8_t imu_id;
    TwoWire* wire;
    int sda_pin;
    int scl_pin;
    uint8_t address;
    uint8_t who_am_i;
};

ImuDevice imu_create(uint8_t imu_id, TwoWire* wire, int sda_pin, int scl_pin);
bool imu_begin(ImuDevice& device);
bool imu_recover(ImuDevice& device);
ImuRead imu_read(const ImuDevice& device);
