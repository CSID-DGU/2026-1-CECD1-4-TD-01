#include "config_store.h"

#include <EEPROM.h>
#include <cstring>

namespace {

constexpr uint32_t kMagic = 0x494f5443;  // IOTC
constexpr uint32_t kClearedMagic = 0x434c5244;  // CLRD
constexpr uint16_t kVersion = 1;

struct __attribute__((packed)) StoredConfig {
  uint32_t magic;
  uint16_t version;
  uint16_t size;
  char ssid[33];
  char password[65];
  char jetsonHost[64];
  uint16_t jetsonPort;
  uint16_t reserved;
  uint32_t checksum;
};

uint32_t checksum(const StoredConfig& config) {
  const auto* bytes = reinterpret_cast<const uint8_t*>(&config);
  const size_t length = offsetof(StoredConfig, checksum);
  uint32_t value = 2166136261UL;
  for (size_t i = 0; i < length; ++i) {
    value ^= bytes[i];
    value *= 16777619UL;
  }
  return value;
}

bool recordIsValid(const StoredConfig& record) {
  return record.magic == kMagic && record.version == kVersion &&
         record.size == sizeof(StoredConfig) &&
         record.checksum == checksum(record) && record.ssid[32] == '\0' &&
         record.password[64] == '\0' && record.jetsonHost[63] == '\0' &&
         record.jetsonPort > 0;
}

bool recordIsCleared(const StoredConfig& record) {
  return record.magic == kClearedMagic && record.version == kVersion &&
         record.size == sizeof(StoredConfig) &&
         record.checksum == checksum(record);
}

BridgeConfig fromRecord(const StoredConfig& record) {
  BridgeConfig config;
  config.ssid = record.ssid;
  config.password = record.password;
  config.jetsonHost = record.jetsonHost;
  config.jetsonPort = record.jetsonPort;
  return config;
}

}  // namespace

bool ConfigStore::begin() {
  EEPROM.begin(kEepromSize);
  return true;
}

BridgeConfig ConfigStore::load(const BridgeConfig& fallback,
                               bool* loadedFromFlash) {
  StoredConfig record{};
  EEPROM.get(0, record);
  const bool cleared = recordIsCleared(record);
  const bool valid = recordIsValid(record);
  if (loadedFromFlash) {
    *loadedFromFlash = valid || cleared;
  }
  if (cleared) {
    return BridgeConfig{};
  }
  if (valid) {
    return fromRecord(record);
  }

  if (fallback.isValid()) {
    save(fallback);
  }
  return fallback;
}

bool ConfigStore::save(const BridgeConfig& config) {
  if (!config.isValid()) {
    return false;
  }

  StoredConfig record{};
  record.magic = kMagic;
  record.version = kVersion;
  record.size = sizeof(StoredConfig);
  std::strncpy(record.ssid, config.ssid.c_str(), sizeof(record.ssid) - 1);
  std::strncpy(record.password, config.password.c_str(),
               sizeof(record.password) - 1);
  std::strncpy(record.jetsonHost, config.jetsonHost.c_str(),
               sizeof(record.jetsonHost) - 1);
  record.jetsonPort = config.jetsonPort;
  record.checksum = checksum(record);

  EEPROM.put(0, record);
  return EEPROM.commit();
}

bool ConfigStore::clear() {
  StoredConfig empty{};
  empty.magic = kClearedMagic;
  empty.version = kVersion;
  empty.size = sizeof(StoredConfig);
  empty.checksum = checksum(empty);
  EEPROM.put(0, empty);
  return EEPROM.commit();
}
