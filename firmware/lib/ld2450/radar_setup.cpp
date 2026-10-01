#include "radar_setup.h"

namespace {

// Frames that parse prove the UART already runs at the radar's baud, so only a silent radar is probed again.
RadarSetupAction unconfigured_action(const RadarSetupInput& input) {
    if (input.has_frame) {
        return RadarSetupAction::ConfigureAtCurrentBaud;
    }
    return input.restart_wanted ? RadarSetupAction::ProbeBaud : RadarSetupAction::None;
}

}  // namespace

RadarSetupAction radar_setup_action(const RadarSetupInput& input) {
    if (!input.configured) {
        return unconfigured_action(input);
    }
    return input.restart_wanted ? RadarSetupAction::Restart : RadarSetupAction::None;
}
