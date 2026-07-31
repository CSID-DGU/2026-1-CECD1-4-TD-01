#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="${P4_UVC_PROJECT_DIR:-${script_dir}/build/p4-usb-uvc-test}"
component_version="2.2.0"

if ! command -v idf.py >/dev/null 2>&1; then
  echo "idf.py is not available. Activate ESP-IDF first." >&2
  exit 1
fi

if [[ ! -f "${project_dir}/CMakeLists.txt" ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' EXIT
  (
    cd "${temp_dir}"
    idf.py create-project-from-example \
      "espressif/esp_video=${component_version}:uvc"
  )

  generated_dir="$(find "${temp_dir}" -mindepth 1 -maxdepth 2 \
    -name CMakeLists.txt -print -quit | xargs dirname)"
  if [[ -z "${generated_dir}" || ! -d "${generated_dir}/main" ]]; then
    echo "Could not locate the generated esp_video UVC project." >&2
    exit 1
  fi

  mkdir -p "$(dirname "${project_dir}")"
  cp -a "${generated_dir}" "${project_dir}"
  cp "${script_dir}/sdkconfig.defaults" "${project_dir}/sdkconfig.defaults"
fi

(
  cd "${project_dir}"
  if [[ ! -f sdkconfig ]]; then
    idf.py set-target esp32p4
  fi
  idf.py build
)

if [[ -n "${PORT:-}" ]]; then
  (
    cd "${project_dir}"
    idf.py -p "${PORT}" flash monitor
  )
else
  echo
  echo "Build complete. Flash through the FUSB connector with:"
  echo "  PORT=/dev/ttyACM0 $0"
  echo "or on native Windows:"
  echo "  idf.py -p COM5 flash monitor"
fi
