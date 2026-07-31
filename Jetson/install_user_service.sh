#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CONFIG_HOME=${XDG_CONFIG_HOME:-"$HOME/.config"}
TOKEN_DIR="$CONFIG_HOME/onmom"
TOKEN_FILE="$TOKEN_DIR/jetson_sync_token"
SERVICE_DIR="$CONFIG_HOME/systemd/user"
SERVICE_FILE="$SERVICE_DIR/onmom-derived-insights.service"
OUTPUT_DIR="$BASE_DIR/data"
OUTPUT_FILE="$OUTPUT_DIR/latest_derived_insights.json"
DATABASE_FILE="$OUTPUT_DIR/onmom_context.db"
ADAPTIVE_CONFIG_FILE="$BASE_DIR/adaptive_policy.ini"
IOT_CONFIG_FILE="$BASE_DIR/iot_devices.json"
PYTHON_BIN=$(command -v python3)

case "$BASE_DIR$TOKEN_FILE$OUTPUT_DIR" in
    *" "*)
        printf 'Jetson install paths containing spaces are not supported by this user service.\n' >&2
        exit 1
        ;;
esac

umask 077
mkdir -p "$TOKEN_DIR" "$SERVICE_DIR" "$OUTPUT_DIR"
chmod 700 "$TOKEN_DIR" "$OUTPUT_DIR"

if [ ! -s "$TOKEN_FILE" ]; then
    "$PYTHON_BIN" -c 'import secrets; print(secrets.token_urlsafe(32))' > "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"

"$PYTHON_BIN" -m py_compile \
    "$BASE_DIR/adaptive_config.py" \
    "$BASE_DIR/analysis_snapshot.py" \
    "$BASE_DIR/guardian_alert.py" \
    "$BASE_DIR/iot_control.py" \
    "$BASE_DIR/context_engine.py" \
    "$BASE_DIR/derived_insight_server.py" \
    "$BASE_DIR/camera_analysis_bridge.py" \
    "$BASE_DIR/onmom_client.py"

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=On-mom persistent adaptive context engine
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$BASE_DIR
ExecStart=$PYTHON_BIN $BASE_DIR/derived_insight_server.py --token-file $TOKEN_FILE --output $OUTPUT_FILE --database $DATABASE_FILE --adaptive-config $ADAPTIVE_CONFIG_FILE --iot-config $IOT_CONFIG_FILE
Restart=always
RestartSec=5
TimeoutStopSec=15
KillSignal=SIGINT
UMask=0077
NoNewPrivileges=true
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF

if command -v systemd-analyze >/dev/null 2>&1; then
    systemd-analyze --user verify "$SERVICE_FILE"
fi

systemctl --user daemon-reload
systemctl --user reset-failed onmom-derived-insights.service 2>/dev/null || true
systemctl --user enable onmom-derived-insights.service
systemctl --user restart onmom-derived-insights.service

printf '\nOn-mom persistent context engine is installed and running.\n'
printf 'Service status: systemctl --user status onmom-derived-insights.service\n'
printf 'Health: curl http://localhost:8765/health\n'
printf 'Camera bridge: udp://127.0.0.1:8766 (derived samples only)\n'
printf 'Database: %s\n' "$DATABASE_FILE"
printf 'Adaptive config: %s\n' "$ADAPTIVE_CONFIG_FILE"
printf 'IoT config: %s\n' "$IOT_CONFIG_FILE"
printf 'Token file: %s (mode 0600)\n' "$TOKEN_FILE"
printf 'Copy this token into the Android app once:\n'
cat "$TOKEN_FILE"
printf '\n\nFor startup before login, run once:\n'
printf '  sudo loginctl enable-linger %s\n' "$USER"
