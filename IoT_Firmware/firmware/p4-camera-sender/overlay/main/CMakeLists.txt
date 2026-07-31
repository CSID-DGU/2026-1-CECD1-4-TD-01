idf_component_register(
    SRCS
        "m2m_main.c"
        "example_v4l2.c"
        "status_peripherals.c"
        "uart_transport.c"
    INCLUDE_DIRS "."
    REQUIRES
        esp_driver_uart
        esp_driver_gpio
        esp_driver_i2c
        esp_driver_ledc
        esp_timer
        example_video_common
)

