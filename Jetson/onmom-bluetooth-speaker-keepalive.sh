#!/usr/bin/env bash
# Keeps a paired Bluetooth speaker connected and makes it the PipeWire default
# only when it first appears. It does not override a later manual output choice.
set -u

MAC_ADDRESS="F8:5C:7E:2A:1E:98"
NODE_NAME="bluez_output.F8_5C_7E_2A_1E_98.1"
POLL_SECONDS=10
was_available=0

find_sink_id() {
  wpctl status -n 2>/dev/null | awk -v node="$NODE_NAME" '
    index($0, node) && /\[vol:/ {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^[0-9]+\.$/) {
          sub(/\.$/, "", $i)
          print $i
          exit
        }
      }
    }
  '
}

while true; do
  if bluetoothctl info "$MAC_ADDRESS" 2>/dev/null | grep -q "Connected: yes"; then
    sink_id="$(find_sink_id)"
    if [ -n "$sink_id" ]; then
      if [ "$was_available" -eq 0 ]; then
        wpctl set-default "$sink_id" >/dev/null 2>&1 || true
      fi
      was_available=1
    fi
  else
    was_available=0
    bluetoothctl connect "$MAC_ADDRESS" >/dev/null 2>&1 || true
  fi
  sleep "$POLL_SECONDS"
done
