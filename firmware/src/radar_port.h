#pragma once

#include <Arduino.h>
#include <driver/uart.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "bundler.h"
#include "frame_gaps.h"
#include "ld2450_commands.h"
#include "ld2450_frame.h"
#include "radar_watchdog.h"

struct RadarPort {
    uint8_t radar_id;
    HardwareSerial* serial;
    uart_port_t uart;
    int8_t rx_pin;
    int8_t tx_pin;
    uint32_t baud;
    FirmwareText firmware;
    FrameParser parser;
    RadarWatchdog watchdog;
    uint16_t bad_frames;
    uint32_t frames_ok;
    bool restart_pending;
    uint32_t restart_due_ms;
    FrameGaps gaps;
    FrameGaps finished_gaps;
    uint32_t gaps_started_ms;
};

struct RadarSnapshot {
    bool configured;
    FirmwareText firmware;
    uint32_t baud;
    RadarWatchdog watchdog;
    StatusEntry status;
    uint32_t frames_ok;
    FrameGaps last_window;
};

RadarPort radar_port_create(uint8_t radar_id, HardwareSerial* serial, uart_port_t uart, int8_t rx_pin, int8_t tx_pin);
void radar_port_start_task(const RadarPort& port, QueueHandle_t frames);
void radar_port_request_restart(uint8_t radar_id);
RadarSnapshot radar_port_snapshot(uint8_t radar_id);
bool radar_snapshot_alive(const RadarSnapshot& snapshot, uint32_t now_ms);
uint32_t radar_port_queue_overflows();
