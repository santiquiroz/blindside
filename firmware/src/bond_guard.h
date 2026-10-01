#pragma once

#include <stdint.h>

#include "bond_store.h"

struct BondGuardEvents {
    uint32_t evicted;
    uint32_t refused;
};

void bond_guard_install();
void bond_guard_trust(const TrustedBonds& trusted);
BondGuardEvents bond_guard_take_events();
