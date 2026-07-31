# ESP-12N UART-to-Wi-Fi bridge

This firmware deliberately produces no debug text on UART0 because UART0 is the
video data channel.

1. Copy `include/secrets.example.h` to `include/secrets.h`.
2. Fill in the 2.4 GHz Wi-Fi credentials and the Jetson LAN IP.
3. Install PlatformIO and run `pio run -t upload`.
4. Connect ESP-12N `GPIO3/RXD0` to the selected ESP32-P4 TX pin.

Electrical requirements:

- Use a regulated 3.3 V supply capable of at least 500 mA.
- Connect both grounds.
- Never feed a 5 V UART signal into either module.
- For a bare ESP-12N, follow its required EN/GPIO0/GPIO2/GPIO15 boot straps.
- Keep the UART wire short. Start at 921600 baud if a jumper-wire build is
  unreliable, and set the same baud in both firmwares.

UART0 is also the flashing interface. Disconnect the P4 TX wire, or hold the P4
in reset, while flashing the ESP-12N.

