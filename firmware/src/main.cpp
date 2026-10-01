#include <Arduino.h>

#include "blindside_config.h"

void setup() {
    Serial.begin(config::kSerialBaud);
    Serial.printf("blindside fw %s\n", config::kFirmwareVersion);
}

void loop() {}
