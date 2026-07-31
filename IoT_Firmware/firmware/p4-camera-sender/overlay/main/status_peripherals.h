#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

esp_err_t status_peripherals_init(void);
void status_screen_boot(const char *message);
void status_screen_stream(uint32_t fps, uint32_t dropped_frames);
void status_speaker_beep(uint32_t frequency_hz, uint32_t duration_ms);
bool status_wifi_connected(void);