#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

esp_err_t video_uart_init(void);
esp_err_t video_uart_send_jpeg_frame(const uint8_t *data, size_t size,
                                     uint32_t frame_sequence,
                                     uint32_t timestamp_ms);

