# WT9932P4-TINY solderless USB camera test

This test uses both USB-C connectors and requires no GPIO header:

- `FUSB`: power, firmware download and USB Serial/JTAG monitor.
- `HUSB`: USB 2.0 High-Speed UVC camera output.

The configuration is adapted for the attached Raspberry Pi Camera v1-compatible
OV5647 module: SCCB SCL GPIO8, SCCB SDA GPIO7, 1280x960 at 30 fps, H.264.
The board's ESP32-P4 is revision 1.3, so the firmware is built for the
pre-v3 silicon family with minimum supported revision 1.0.

## Safety

Power the board off before touching the FPC cable. Use the connector marked
`CSI`, not `DSI`. Wireless-Tag warns that the FPC contact/power orientation
must be checked carefully; a reversed cable can heat or damage the camera.

## Build and flash

Activate ESP-IDF 5.4.2 or newer, then:

```bash
chmod +x prepare-build-flash.sh
./prepare-build-flash.sh
PORT=/dev/ttyACM0 ./prepare-build-flash.sh
```

On native Windows, install the official EIM once and use the checked-in
PowerShell helper. Build artifacts are placed under `C:\tmp\p4uvc` to avoid
Windows path-length failures:

```powershell
winget install --id Espressif.EIM-CLI --exact
# One-time installation; already completed on this development PC:
eim install -i v5.5.4 -t esp32p4

Set-ExecutionPolicy -Scope Process Bypass
.\prepare-build-flash.ps1 -BuildOnly
.\prepare-build-flash.ps1 -Port COM5
```

After the FUSB monitor reports `UVC Device Start`, connect HUSB to Windows with
a second data-capable cable. List DirectShow cameras and preview the UVC stream:

```powershell
ffmpeg -hide_banner -list_devices true -f dshow -i dummy
ffplay -f dshow -i video="USB Camera"
```

Use the exact camera name printed by the first command.

If automatic download mode fails, hold BOOT, tap RESET, release RESET, and
then release BOOT before retrying the flash.

Expected sensor log:

```text
ov5647: Detected Camera sensor PID=0x5647
UVC Device Start
```

If the OV5647 line does not appear, stop and check the camera power/FPC before
enabling a different driver.

## View from the Jetson

Keep FUSB connected for logs. Connect HUSB to the Jetson with a second
data-capable USB-C cable.

```bash
sudo apt-get install -y v4l-utils ffmpeg
v4l2-ctl --list-devices
v4l2-ctl --device=/dev/video0 --list-formats-ext
ffplay -f v4l2 -input_format h264 \
  -video_size 1280x960 -framerate 30 /dev/video0
```

To feed the existing MediaMTX and pose stack:

```bash
docker compose up -d mediamtx pose
./scripts/usb-camera-to-rtsp.sh /dev/video0
```

The existing endpoints remain:

```text
rtsp://JETSON_IP:8554/camera
http://JETSON_IP:8080/stream.mjpg
```
