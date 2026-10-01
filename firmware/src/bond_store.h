#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>

#include "bond_rules.h"

struct TrustedBonds {
    NimBLEAddress identities[kMaxBonds];
    LinkRole roles[kMaxBonds];
    size_t count;
};

TrustedBonds bond_store_load();
int bond_store_index_of(const TrustedBonds& bonds, const NimBLEAddress& identity);
bool bond_store_contains(const TrustedBonds& bonds, const NimBLEAddress& identity);
TrustedBonds bond_store_with(const TrustedBonds& bonds, const NimBLEAddress& identity);
TrustedBonds bond_store_without(const TrustedBonds& bonds, size_t index);
TrustedBonds bond_store_with_role(const TrustedBonds& bonds, size_t index, LinkRole role);
void bond_store_save_roles(const TrustedBonds& bonds);
void bond_store_forget(const NimBLEAddress& identity);
void bond_store_forget_untrusted(const TrustedBonds& bonds);
void bond_store_forget_all();
void bond_store_refresh_whitelist(const TrustedBonds& bonds);
