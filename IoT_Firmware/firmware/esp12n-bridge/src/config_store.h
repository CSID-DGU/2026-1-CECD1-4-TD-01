#pragma once

#include <Arduino.h>

struct BridgeConfig {
  String ssid;
  String password;
  String jetsonHost;
  uint16_t jetsonPort = 9000;

  bool isValid() const {
    return ssid.length() > 0 && ssid.length() <= 32 &&
           password.length() <= 63 && jetsonHost.length() > 0 &&
           jetsonHost.length() <= 63 && jetsonPort > 0;
  }
};

class ConfigStore {
 public:
  bool begin();
  BridgeConfig load(const BridgeConfig& fallback, bool* loadedFromFlash = nullptr);
  bool save(const BridgeConfig& config);
  bool clear();

 private:
  static constexpr size_t kEepromSize = 512;
};
