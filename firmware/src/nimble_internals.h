#pragma once

#include <stdint.h>

void nimble_set_cccd_permissions(uint8_t flags);
uint16_t nimble_link_backlog(uint16_t conn_handle);
uint16_t nimble_free_acl_buffers();
uint32_t nimble_host_stack_free();
