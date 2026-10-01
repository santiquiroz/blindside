#include "status_led.h"

#include <Arduino.h>

#include "blindside_config.h"

void status_led_begin() {
    pinMode(config::kStatusLedPin, OUTPUT);
    digitalWrite(config::kStatusLedPin, LOW);
}

void status_led_show(const LedInputs& inputs) {
    digitalWrite(config::kStatusLedPin, led_on(inputs) ? HIGH : LOW);
}
