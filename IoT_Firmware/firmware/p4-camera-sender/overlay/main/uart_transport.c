#include "uart_transport.h"

#include <string.h>

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "freertos/FreeRTOS.h"
#include "sdkconfig.h"

#define P4V1_MAGIC "P4V1"
#define P4V1_VERSION 1
#define P4V1_FLAG_START 0x01
#define P4V1_FLAG_END 0x02
#define P4V1_FLAG_KEYFRAME 0x04
#define P4V1_FLAG_JPEG 0x08
#define P4V1_MAX_PAYLOAD 512
#define P4V1_CHUNK_GAP_US 5000

static const char *TAG = "video_uart";
static const uart_port_t k_uart = (uart_port_t)CONFIG_P4SENDER_UART_PORT;

typedef struct __attribute__((packed)) {
    uint8_t magic[4];
    uint8_t version;
    uint8_t flags;
    uint16_t header_length;
    uint32_t frame_sequence;
    uint32_t timestamp_ms;
    uint16_t payload_length;
    uint16_t reserved;
    uint32_t payload_crc32;
} p4v1_header_t;

_Static_assert(sizeof(p4v1_header_t) == 24, "P4V1 header must be 24 bytes");

static uint32_t payload_crc32(const uint8_t *data, size_t length)
{
    uint32_t crc = 0xffffffffU;
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            const uint32_t mask = (uint32_t)-(int32_t)(crc & 1U);
            crc = (crc >> 1) ^ (0xedb88320U & mask);
        }
    }
    return ~crc;
}

static esp_err_t write_all(const void *data, size_t length)
{
    const int written = uart_write_bytes(k_uart, data, length);
    if (written != (int)length) {
        ESP_LOGE(TAG, "short UART write: %d/%u", written, (unsigned)length);
        return ESP_FAIL;
    }
    return ESP_OK;
}

esp_err_t video_uart_init(void)
{
    const uart_config_t config = {
        .baud_rate = CONFIG_P4SENDER_UART_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    ESP_RETURN_ON_ERROR(
        uart_driver_install(k_uart, 2048, 16384, 0, NULL, 0),
        TAG, "UART driver install failed");
    ESP_RETURN_ON_ERROR(uart_param_config(k_uart, &config),
                        TAG, "UART configuration failed");
    ESP_RETURN_ON_ERROR(
        uart_set_pin(k_uart, CONFIG_P4SENDER_UART_TX_GPIO,
                     CONFIG_P4SENDER_UART_RX_GPIO,
                     UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE),
        TAG, "UART pin assignment failed");
    ESP_RETURN_ON_ERROR(
        gpio_set_drive_capability((gpio_num_t)CONFIG_P4SENDER_UART_TX_GPIO,
                                  GPIO_DRIVE_CAP_3),
        TAG, "UART TX drive strength setup failed");

    ESP_LOGI(TAG, "video UART ready: port=%d tx=%d rx=%d baud=%d",
             (int)k_uart, CONFIG_P4SENDER_UART_TX_GPIO,
             CONFIG_P4SENDER_UART_RX_GPIO, CONFIG_P4SENDER_UART_BAUD);
    return ESP_OK;
}

esp_err_t video_uart_send_jpeg_frame(const uint8_t *data, size_t size,
                                     uint32_t frame_sequence,
                                     uint32_t timestamp_ms)
{
    if (!data || size == 0 || size > UINT32_MAX) {
        return ESP_ERR_INVALID_ARG;
    }

    typedef struct __attribute__((packed)) {
        uint8_t magic[4];
        uint32_t jpeg_size;
        uint32_t jpeg_crc32;
    } jpeg_envelope_t;

    const jpeg_envelope_t envelope = {
        .magic = {'J', 'P', 'G', '1'},
        .jpeg_size = (uint32_t)size,
        .jpeg_crc32 = payload_crc32(data, size),
    };

    size_t offset = 0;
    bool first = true;
    while (first || offset < size) {
        uint8_t payload[P4V1_MAX_PAYLOAD];
        size_t payload_size = 0;

        if (first) {
            memcpy(payload, &envelope, sizeof(envelope));
            payload_size = sizeof(envelope);
        }

        const size_t capacity = P4V1_MAX_PAYLOAD - payload_size;
        const size_t remaining = size - offset;
        const size_t data_size = remaining > capacity ? capacity : remaining;
        memcpy(payload + payload_size, data + offset, data_size);
        payload_size += data_size;
        offset += data_size;

        uint8_t flags = P4V1_FLAG_JPEG;
        if (first) {
            flags |= P4V1_FLAG_START;
        }
        if (offset == size) {
            flags |= P4V1_FLAG_END;
        }

        p4v1_header_t header = {
            .version = P4V1_VERSION,
            .flags = flags,
            .header_length = sizeof(p4v1_header_t),
            .frame_sequence = frame_sequence,
            .timestamp_ms = timestamp_ms,
            .payload_length = (uint16_t)payload_size,
            .reserved = 0,
            .payload_crc32 = payload_crc32(payload, payload_size),
        };
        memcpy(header.magic, P4V1_MAGIC, sizeof(header.magic));

        ESP_RETURN_ON_ERROR(write_all(&header, sizeof(header)),
                            TAG, "header write failed");
        ESP_RETURN_ON_ERROR(write_all(payload, payload_size),
                            TAG, "payload write failed");
        ESP_RETURN_ON_ERROR(
            uart_wait_tx_done(k_uart, pdMS_TO_TICKS(100)),
            TAG, "UART chunk flush failed");
        esp_rom_delay_us(P4V1_CHUNK_GAP_US);
        first = false;
    }

    return ESP_OK;
}