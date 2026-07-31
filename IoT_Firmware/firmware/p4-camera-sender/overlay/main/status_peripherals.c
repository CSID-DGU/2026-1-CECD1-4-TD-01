#include "status_peripherals.h"

#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/ledc.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

#define OLED_WIDTH 128
#define OLED_PAGES 4
#define OLED_BUFFER_SIZE (OLED_WIDTH * OLED_PAGES)
#define OLED_I2C_TIMEOUT_MS 100

static const char *TAG = "status_peripherals";
static i2c_master_bus_handle_t oled_bus;
static i2c_master_dev_handle_t oled_device;
static uint8_t oled_buffer[OLED_BUFFER_SIZE];
static bool speaker_ready;

static const uint8_t digit_font[10][5] = {
    {0x3e, 0x51, 0x49, 0x45, 0x3e}, {0x00, 0x42, 0x7f, 0x40, 0x00},
    {0x42, 0x61, 0x51, 0x49, 0x46}, {0x21, 0x41, 0x45, 0x4b, 0x31},
    {0x18, 0x14, 0x12, 0x7f, 0x10}, {0x27, 0x45, 0x45, 0x45, 0x39},
    {0x3c, 0x4a, 0x49, 0x49, 0x30}, {0x01, 0x71, 0x09, 0x05, 0x03},
    {0x36, 0x49, 0x49, 0x49, 0x36}, {0x06, 0x49, 0x49, 0x29, 0x1e},
};

static const uint8_t upper_font[26][5] = {
    {0x7e, 0x11, 0x11, 0x11, 0x7e}, {0x7f, 0x49, 0x49, 0x49, 0x36},
    {0x3e, 0x41, 0x41, 0x41, 0x22}, {0x7f, 0x41, 0x41, 0x22, 0x1c},
    {0x7f, 0x49, 0x49, 0x49, 0x41}, {0x7f, 0x09, 0x09, 0x09, 0x01},
    {0x3e, 0x41, 0x49, 0x49, 0x7a}, {0x7f, 0x08, 0x08, 0x08, 0x7f},
    {0x00, 0x41, 0x7f, 0x41, 0x00}, {0x20, 0x40, 0x41, 0x3f, 0x01},
    {0x7f, 0x08, 0x14, 0x22, 0x41}, {0x7f, 0x40, 0x40, 0x40, 0x40},
    {0x7f, 0x02, 0x0c, 0x02, 0x7f}, {0x7f, 0x04, 0x08, 0x10, 0x7f},
    {0x3e, 0x41, 0x41, 0x41, 0x3e}, {0x7f, 0x09, 0x09, 0x09, 0x06},
    {0x3e, 0x41, 0x51, 0x21, 0x5e}, {0x7f, 0x09, 0x19, 0x29, 0x46},
    {0x46, 0x49, 0x49, 0x49, 0x31}, {0x01, 0x01, 0x7f, 0x01, 0x01},
    {0x3f, 0x40, 0x40, 0x40, 0x3f}, {0x1f, 0x20, 0x40, 0x20, 0x1f},
    {0x3f, 0x40, 0x38, 0x40, 0x3f}, {0x63, 0x14, 0x08, 0x14, 0x63},
    {0x07, 0x08, 0x70, 0x08, 0x07}, {0x61, 0x51, 0x49, 0x45, 0x43},
};

static void glyph_for(char character, uint8_t glyph[5])
{
    memset(glyph, 0, 5);
    if (character >= '0' && character <= '9') {
        memcpy(glyph, digit_font[character - '0'], 5);
        return;
    }
    if (character >= 'a' && character <= 'z') {
        character -= ('a' - 'A');
    }
    if (character >= 'A' && character <= 'Z') {
        memcpy(glyph, upper_font[character - 'A'], 5);
        return;
    }
    switch (character) {
    case ':': glyph[1] = 0x36; glyph[2] = 0x36; break;
    case '.': glyph[2] = 0x60; glyph[3] = 0x60; break;
    case '-': glyph[1] = 0x08; glyph[2] = 0x08; glyph[3] = 0x08; break;
    case '/':
        glyph[0] = 0x20; glyph[1] = 0x10; glyph[2] = 0x08;
        glyph[3] = 0x04; glyph[4] = 0x02;
        break;
    default: break;
    }
}

static esp_err_t oled_transmit(const uint8_t *data, size_t length)
{
    if (!oled_device) {
        return ESP_ERR_INVALID_STATE;
    }
    return i2c_master_transmit(oled_device, data, length, OLED_I2C_TIMEOUT_MS);
}

static esp_err_t oled_commands(const uint8_t *commands, size_t count)
{
    uint8_t packet[32] = {0};
    if (count > sizeof(packet) - 1) {
        return ESP_ERR_INVALID_SIZE;
    }
    memcpy(packet + 1, commands, count);
    return oled_transmit(packet, count + 1);
}

static esp_err_t oled_flush(void)
{
    const uint8_t address_commands[] = {
        0x21, 0x00, OLED_WIDTH - 1, 0x22, 0x00, OLED_PAGES - 1,
    };
    esp_err_t result = oled_commands(address_commands, sizeof(address_commands));
    if (result != ESP_OK) {
        return result;
    }

    uint8_t packet[33];
    packet[0] = 0x40;
    for (size_t offset = 0; offset < sizeof(oled_buffer); offset += 32) {
        memcpy(packet + 1, oled_buffer + offset, 32);
        result = oled_transmit(packet, sizeof(packet));
        if (result != ESP_OK) {
            return result;
        }
    }
    return ESP_OK;
}

static void oled_draw_text(unsigned row, const char *text)
{
    if (row >= OLED_PAGES || !text) {
        return;
    }
    size_t x = 1;
    while (*text && x + 5 < OLED_WIDTH) {
        uint8_t glyph[5];
        glyph_for(*text++, glyph);
        memcpy(&oled_buffer[row * OLED_WIDTH + x], glyph, sizeof(glyph));
        x += 6;
    }
}

static void oled_render(const char *line0, const char *line1,
                        const char *line2, const char *line3)
{
    if (!oled_device) {
        return;
    }
    memset(oled_buffer, 0, sizeof(oled_buffer));
    oled_draw_text(0, line0);
    oled_draw_text(1, line1);
    oled_draw_text(2, line2);
    oled_draw_text(3, line3);
    const esp_err_t result = oled_flush();
    if (result != ESP_OK) {
        ESP_LOGW(TAG, "OLED refresh failed: %s", esp_err_to_name(result));
    }
}

static esp_err_t oled_init(void)
{
    const i2c_master_bus_config_t bus_config = {
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .i2c_port = 1,
        .scl_io_num = CONFIG_P4SENDER_OLED_SCL_GPIO,
        .sda_io_num = CONFIG_P4SENDER_OLED_SDA_GPIO,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    esp_err_t result = i2c_new_master_bus(&bus_config, &oled_bus);
    if (result != ESP_OK) {
        return result;
    }

    uint8_t address = 0;
    for (uint8_t candidate = 0x3c; candidate <= 0x3d; ++candidate) {
        if (i2c_master_probe(oled_bus, candidate, 50) == ESP_OK) {
            address = candidate;
            break;
        }
    }
    if (!address) {
        i2c_del_master_bus(oled_bus);
        oled_bus = NULL;
        return ESP_ERR_NOT_FOUND;
    }

    const i2c_device_config_t device_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = address,
        .scl_speed_hz = 400000,
    };
    result = i2c_master_bus_add_device(oled_bus, &device_config, &oled_device);
    if (result != ESP_OK) {
        i2c_del_master_bus(oled_bus);
        oled_bus = NULL;
        return result;
    }

    const uint8_t init_commands[] = {
        0xae, 0xd5, 0x80, 0xa8, 0x1f, 0xd3, 0x00, 0x40,
        0x8d, 0x14, 0x20, 0x00, 0xa1, 0xc8, 0xda, 0x02,
        0x81, 0x8f, 0xd9, 0xf1, 0xdb, 0x40, 0xa4, 0xa6, 0xaf,
    };
    result = oled_commands(init_commands, sizeof(init_commands));
    if (result != ESP_OK) {
        return result;
    }

    memset(oled_buffer, 0, sizeof(oled_buffer));
    result = oled_flush();
    if (result == ESP_OK) {
        ESP_LOGI(TAG, "OLED ready: SSD1306 128x32 address=0x%02x SDA=%d SCL=%d",
                 address, CONFIG_P4SENDER_OLED_SDA_GPIO,
                 CONFIG_P4SENDER_OLED_SCL_GPIO);
    }
    return result;
}

static esp_err_t speaker_init(void)
{
    const ledc_timer_config_t timer = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_10_BIT,
        .timer_num = LEDC_TIMER_0,
        .freq_hz = 1000,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    esp_err_t result = ledc_timer_config(&timer);
    if (result != ESP_OK) {
        return result;
    }

    const ledc_channel_config_t channel = {
        .gpio_num = CONFIG_P4SENDER_SPEAKER_GPIO,
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = LEDC_CHANNEL_0,
        .intr_type = LEDC_INTR_DISABLE,
        .timer_sel = LEDC_TIMER_0,
        .duty = 0,
        .hpoint = 0,
    };
    result = ledc_channel_config(&channel);
    if (result == ESP_OK) {
        speaker_ready = true;
        ESP_LOGI(TAG, "speaker PWM ready: GPIO%d", CONFIG_P4SENDER_SPEAKER_GPIO);
    }
    return result;
}

esp_err_t status_peripherals_init(void)
{
    const gpio_config_t state_config = {
        .pin_bit_mask = 1ULL << CONFIG_P4SENDER_DT06_STATE_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    esp_err_t result = gpio_config(&state_config);
    if (result != ESP_OK) {
        ESP_LOGW(TAG, "DT-06 STATE GPIO init failed: %s", esp_err_to_name(result));
    }

    result = speaker_init();
    if (result != ESP_OK) {
        ESP_LOGW(TAG, "speaker init failed: %s", esp_err_to_name(result));
    }

    result = oled_init();
    if (result != ESP_OK) {
        ESP_LOGW(TAG, "OLED not available on SDA=%d SCL=%d: %s",
                 CONFIG_P4SENDER_OLED_SDA_GPIO,
                 CONFIG_P4SENDER_OLED_SCL_GPIO, esp_err_to_name(result));
    }
    return ESP_OK;
}

void status_screen_boot(const char *message)
{
    oled_render("IOT CAMERA", message ? message : "BOOTING",
                "OLED: OK", "PLEASE WAIT");
}

void status_screen_stream(uint32_t fps, uint32_t dropped_frames)
{
    char camera_line[22];
    char stats_line[22];
    snprintf(camera_line, sizeof(camera_line), "CAM 1280X960");
    snprintf(stats_line, sizeof(stats_line), "SNAP:%lu/S DROP:%lu",
             (unsigned long)fps, (unsigned long)dropped_frames);
    oled_render("IOT CAMERA STREAM", camera_line, stats_line,
                status_wifi_connected() ? "WIFI: LINK" : "WIFI: WAIT");
}

void status_speaker_beep(uint32_t frequency_hz, uint32_t duration_ms)
{
    if (!speaker_ready || frequency_hz == 0 || duration_ms == 0) {
        return;
    }
    if (ledc_set_freq(LEDC_LOW_SPEED_MODE, LEDC_TIMER_0, frequency_hz) == 0) {
        return;
    }
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, 256);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
    vTaskDelay(pdMS_TO_TICKS(duration_ms));
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, 0);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
}

bool status_wifi_connected(void)
{
    return gpio_get_level(CONFIG_P4SENDER_DT06_STATE_GPIO) == 0;
}