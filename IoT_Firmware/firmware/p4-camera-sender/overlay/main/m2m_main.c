/*
 * Camera/JPEG plumbing comes from Espressif's esp_video m2m example.
 * This replacement app sends CRC-protected JPEG snapshots over UART.
 */

#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>

#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "linux/videodev2.h"

#include "example_v4l2.h"
#include "status_peripherals.h"
#include "sdkconfig.h"
#include "uart_transport.h"

static const char *TAG = "p4_camera_sender";

/* WT9932P4-TINY J2: CAM_IO0=GPIO0, SCCB SDA=GPIO7, SCL=GPIO8. */
#define CAMERA_POWER_ENABLE_GPIO GPIO_NUM_0
#define CAMERA_SCCB_SDA_GPIO     GPIO_NUM_7
#define CAMERA_SCCB_SCL_GPIO     GPIO_NUM_8

static esp_err_t camera_power_enable(void)
{
    const gpio_config_t io_config = {
        .pin_bit_mask = 1ULL << CAMERA_POWER_ENABLE_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };

    ESP_RETURN_ON_ERROR(gpio_config(&io_config), TAG,
                        "failed to configure CAM_IO0 power-enable");
    ESP_RETURN_ON_ERROR(gpio_set_level(CAMERA_POWER_ENABLE_GPIO, 1), TAG,
                        "failed to set CAM_IO0 high");
    ESP_LOGI(TAG, "CAM_IO0/GPIO0 set HIGH (camera power enabled)");
    vTaskDelay(pdMS_TO_TICKS(100));
    return ESP_OK;
}

static esp_err_t scan_camera_sccb(void)
{
    const i2c_master_bus_config_t bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = 0,
        .scl_io_num = CAMERA_SCCB_SCL_GPIO,
        .sda_io_num = CAMERA_SCCB_SDA_GPIO,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    i2c_master_bus_handle_t bus = NULL;
    esp_err_t ret = i2c_new_master_bus(&bus_config, &bus);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "failed to create SCCB/I2C bus: %s", esp_err_to_name(ret));
        return ret;
    }

    int devices_found = 0;
    ESP_LOGI(TAG, "Scanning camera SCCB/I2C on SDA=GPIO7, SCL=GPIO8...");
    for (uint8_t address = 0x08; address <= 0x77; ++address) {
        if (i2c_master_probe(bus, address, 20) == ESP_OK) {
            ESP_LOGI(TAG, "SCCB/I2C ACK at 7-bit address 0x%02X", address);
            ++devices_found;
        }
    }

    const esp_err_t delete_ret = i2c_del_master_bus(bus);
    if (delete_ret != ESP_OK) {
        ESP_LOGW(TAG, "failed to release SCCB/I2C bus: %s",
                 esp_err_to_name(delete_ret));
    }
    if (devices_found == 0) {
        ESP_LOGE(TAG, "NO SCCB/I2C DEVICE FOUND");
        return ESP_ERR_NOT_FOUND;
    }
    ESP_LOGI(TAG, "SCCB/I2C scan complete: %d device(s) found", devices_found);
    return ESP_OK;
}

static void halt_without_reboot(const char *reason, esp_err_t error)
{
    ESP_LOGE(TAG, "%s: %s (0x%x)", reason, esp_err_to_name(error), error);
    ESP_LOGE(TAG, "Diagnostic halted safely; reset or reflash to retry");
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void app_main(void)
{
    example_camera_handle_t camera = NULL;
    example_jpeg_encoder_handle_t encoder = NULL;
    const example_camera_config_t camera_config = {
        .width = CONFIG_P4SENDER_WIDTH,
        .height = CONFIG_P4SENDER_HEIGHT,
        .format = 0,
    };
    const example_jpeg_config_t encoder_config = {
        .width = CONFIG_P4SENDER_WIDTH,
        .height = CONFIG_P4SENDER_HEIGHT,
        .input_format = 0,
        .quality = CONFIG_P4SENDER_JPEG_QUALITY,
    };

    esp_err_t init_result = camera_power_enable();
    if (init_result != ESP_OK) {
        halt_without_reboot("camera power enable failed", init_result);
    }

    status_peripherals_init();
    status_screen_boot("CHECKING CAMERA");
    status_speaker_beep(880, 120);

    init_result = scan_camera_sccb();
    if (init_result != ESP_OK) {
        status_screen_boot("CAMERA ERROR");
        status_speaker_beep(220, 500);
        halt_without_reboot("camera SCCB/I2C scan failed", init_result);
    }
    status_screen_boot("CAMERA FOUND");

    ESP_ERROR_CHECK(video_uart_init());
    ESP_ERROR_CHECK(open_camera(&camera_config, &camera));
    ESP_ERROR_CHECK(open_jpeg_encoder(&encoder_config, &encoder));
    ESP_ERROR_CHECK(jpeg_encode_connect(camera, encoder));

    status_screen_stream(0, 0);
    status_speaker_beep(1200, 100);
    vTaskDelay(pdMS_TO_TICKS(60));
    status_speaker_beep(1600, 120);

    const int64_t interval_us =
        (int64_t)CONFIG_P4SENDER_SNAPSHOT_INTERVAL_MS * 1000LL;
    int64_t next_capture_us = esp_timer_get_time();
    uint32_t sequence = 0;
    uint32_t sent_frames = 0;
    uint32_t dropped_frames = 0;
    int64_t stats_started_us = next_capture_us;

    ESP_LOGI(TAG,
             "JPEG snapshots %" PRIu32 "x%" PRIu32
             " quality=%d interval=%dms UART=%d",
             camera_config.width, camera_config.height,
             CONFIG_P4SENDER_JPEG_QUALITY,
             CONFIG_P4SENDER_SNAPSHOT_INTERVAL_MS,
             CONFIG_P4SENDER_UART_BAUD);

    while (true) {
        const int64_t before_wait_us = esp_timer_get_time();
        if (before_wait_us < next_capture_us) {
            const uint32_t wait_ms = (uint32_t)(
                (next_capture_us - before_wait_us + 999LL) / 1000LL);
            vTaskDelay(pdMS_TO_TICKS(wait_ms));
        }

        const int64_t shot_started_us = esp_timer_get_time();
        example_image_t raw = {0};
        example_image_t encoded = {0};

        if (camera_capture_image(&raw) != ESP_OK) {
            ++dropped_frames;
            next_capture_us = esp_timer_get_time() + interval_us;
            continue;
        }
        const int64_t captured_us = esp_timer_get_time();

        const esp_err_t encode_result = jpeg_encode(&raw, &encoded);
        buffer_free(&raw);
        const int64_t encoded_us = esp_timer_get_time();
        if (encode_result != ESP_OK || !encoded.data || encoded.size < 4 ||
            encoded.data[0] != 0xff || encoded.data[1] != 0xd8 ||
            encoded.data[encoded.size - 2] != 0xff ||
            encoded.data[encoded.size - 1] != 0xd9) {
            buffer_free(&encoded);
            ++dropped_frames;
            next_capture_us = esp_timer_get_time() + interval_us;
            continue;
        }

        const uint32_t frame_sequence = sequence++;
        const uint32_t timestamp_ms =
            (uint32_t)(shot_started_us / 1000ULL);
        const size_t jpeg_size = encoded.size;
        esp_err_t send_result = ESP_OK;
        for (int copy = 0; copy < 2; ++copy) {
            const esp_err_t copy_result = video_uart_send_jpeg_frame(
                encoded.data, encoded.size, frame_sequence, timestamp_ms);
            if (copy_result != ESP_OK && send_result == ESP_OK) {
                send_result = copy_result;
            }
        }
        buffer_free(&encoded);
        const int64_t sent_us = esp_timer_get_time();

        if (send_result == ESP_OK) {
            ++sent_frames;
        } else {
            ++dropped_frames;
        }

        ESP_LOGI(TAG,
                 "shot=%" PRIu32 " jpeg=%uB copies=2 capture=%lldms encode=%lldms "
                 "uart=%lldms total=%lldms result=%s",
                 frame_sequence, (unsigned)jpeg_size,
                 (long long)((captured_us - shot_started_us) / 1000LL),
                 (long long)((encoded_us - captured_us) / 1000LL),
                 (long long)((sent_us - encoded_us) / 1000LL),
                 (long long)((sent_us - shot_started_us) / 1000LL),
                 esp_err_to_name(send_result));

        const int64_t elapsed_us = sent_us - stats_started_us;
        if (elapsed_us >= 5000000LL) {
            const uint32_t measured_fps = (uint32_t)(
                ((uint64_t)sent_frames * 1000000ULL +
                 (uint64_t)elapsed_us / 2ULL) /
                (uint64_t)elapsed_us);
            status_screen_stream(measured_fps, dropped_frames);
            ESP_LOGI(TAG, "snapshots sent=%" PRIu32 " dropped=%" PRIu32,
                     sent_frames, dropped_frames);
            sent_frames = 0;
            dropped_frames = 0;
            stats_started_us = sent_us;
        }

        next_capture_us += interval_us;
        if (next_capture_us <= sent_us) {
            next_capture_us = sent_us + 1000LL;
        }
    }
}