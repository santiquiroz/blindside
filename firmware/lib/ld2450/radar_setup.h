#pragma once

#include <stdint.h>

enum class RadarSetupAction : uint8_t { None, Restart, ConfigureAtCurrentBaud, ProbeBaud };

struct RadarSetupInput {
    bool configured;
    bool has_frame;
    bool restart_wanted;
};

RadarSetupAction radar_setup_action(const RadarSetupInput& input);
