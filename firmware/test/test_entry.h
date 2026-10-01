#pragma once

int run_all_tests();

#ifdef ARDUINO
#include <Arduino.h>

void setup() {
    delay(2000);  // lets the serial monitor attach before Unity prints
    run_all_tests();
}

void loop() {}
#else
int main() {
    return run_all_tests();
}
#endif
