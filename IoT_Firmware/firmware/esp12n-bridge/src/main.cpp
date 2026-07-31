#include <Arduino.h>
#include <ArduinoOTA.h>
#include <DNSServer.h>
#include <ESP8266WebServer.h>
#include <ESP8266mDNS.h>
#include <ESP8266WiFi.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#include "secrets.example.h"
#endif

#ifndef DEVICE_HOSTNAME
#define DEVICE_HOSTNAME "iotcam-bridge"
#endif

#ifndef OTA_PASSWORD
#define OTA_PASSWORD ""
#endif

#include "config_store.h"
#include "web_ui.h"

namespace {

constexpr uint32_t kP4Baud = 1000000;
constexpr size_t kSerialRxBuffer = 16384;
constexpr size_t kCopyBuffer = 1460;
constexpr uint32_t kWifiPortalDelayMs = 20000;
constexpr uint32_t kBootPortalWindowMs = 300000;
constexpr uint32_t kTcpRetryMs = 1000;
#ifdef DT06_BUILD
constexpr uint8_t kStatusLed = 4;  // DT-06 LED is active-low on GPIO4.
#else
constexpr uint8_t kStatusLed = LED_BUILTIN;
#endif
constexpr char kSetupPassword[] = "iotcam-setup";

WiFiClient videoClient;
ESP8266WebServer webServer(80);
DNSServer dnsServer;
ConfigStore configStore;
BridgeConfig bridgeConfig;

uint8_t copyBuffer[kCopyBuffer];
String setupApName;
uint32_t disconnectedSinceMs = 0;
uint32_t portalStartedMs = 0;
uint32_t lastDiagnosticsMs = 0;
uint32_t lastTcpAttemptMs = 0;
uint32_t uartBytesReceived = 0;
uint32_t wifiBytesForwarded = 0;
uint32_t wifiBytesReceived = 0;
uint32_t uartBytesForwarded = 0;
uint32_t droppedBytes = 0;
uint32_t tcpConnections = 0;
uint32_t lastUartDataMs = 0;
uint32_t rebootAtMs = 0;
bool portalActive = false;
bool networkServicesStarted = false;

void setLed(bool on) {
  digitalWrite(kStatusLed, on ? LOW : HIGH);
}

void appendJsonString(String& json, const String& value) {
  json += '"';
  for (size_t i = 0; i < value.length(); ++i) {
    const char c = value[i];
    switch (c) {
      case '"': json += F("\\\""); break;
      case '\\': json += F("\\\\"); break;
      case '\n': json += F("\\n"); break;
      case '\r': json += F("\\r"); break;
      case '\t': json += F("\\t"); break;
      default:
        if (static_cast<uint8_t>(c) >= 0x20) {
          json += c;
        }
        break;
    }
  }
  json += '"';
}

void sendJsonError(int status, const String& message) {
  String payload = F("{\"error\":");
  appendJsonString(payload, message);
  payload += '}';
  webServer.send(status, "application/json", payload);
}

void scheduleReboot(uint32_t delayMs = 1200) {
  rebootAtMs = millis() + delayMs;
}

String makeSetupApName() {
  String mac = WiFi.macAddress();
  mac.replace(":", "");
  mac.toUpperCase();
  const String suffix = mac.length() >= 4 ? mac.substring(mac.length() - 4) : mac;
  return String(F("IoTCam-Setup-")) + suffix;
}

void startSetupPortal() {
  if (portalActive) {
    return;
  }

  setupApName = makeSetupApName();
  WiFi.mode(WIFI_AP_STA);
  const bool started = WiFi.softAP(setupApName.c_str(), kSetupPassword);
  if (!started) {
    portalActive = false;
    return;
  }
  dnsServer.setErrorReplyCode(DNSReplyCode::NoError);
  dnsServer.start(53, "*", WiFi.softAPIP());
  portalStartedMs = millis();
  portalActive = true;
}

void stopSetupPortal() {
  if (!portalActive) {
    return;
  }
  dnsServer.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  portalActive = false;
}

void startStation() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.hostname(DEVICE_HOSTNAME);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);
  WiFi.setAutoReconnect(true);
  disconnectedSinceMs = millis();

  if (!bridgeConfig.isValid()) {
    startSetupPortal();
    return;
  }

  if (bridgeConfig.password.length() == 0) {
    WiFi.begin(bridgeConfig.ssid.c_str());
  } else {
    WiFi.begin(bridgeConfig.ssid.c_str(), bridgeConfig.password.c_str());
  }
}

String statusJson() {
  const bool wifiConnected = WiFi.status() == WL_CONNECTED;
  String json;
  json.reserve(640);
  json += F("{\"wifi_connected\":");
  json += wifiConnected ? F("true") : F("false");
  json += F(",\"tcp_connected\":");
  json += videoClient.connected() ? F("true") : F("false");
  json += F(",\"ap_active\":");
  json += portalActive ? F("true") : F("false");
  json += F(",\"ap_ssid\":");
  appendJsonString(json, portalActive ? setupApName : String());
  json += F(",\"wifi_ssid\":");
  appendJsonString(json, wifiConnected ? WiFi.SSID() : bridgeConfig.ssid);
  json += F(",\"ip\":");
  appendJsonString(json, wifiConnected ? WiFi.localIP().toString()
                                       : WiFi.softAPIP().toString());
  json += F(",\"rssi_dbm\":");
  json += wifiConnected ? String(WiFi.RSSI()) : String(0);
  json += F(",\"jetson_host\":");
  appendJsonString(json, bridgeConfig.jetsonHost);
  json += F(",\"jetson_port\":");
  json += String(bridgeConfig.jetsonPort);
  json += F(",\"uart_bytes_received\":");
  json += String(uartBytesReceived);
  json += F(",\"wifi_bytes_forwarded\":");
  json += String(wifiBytesForwarded);
  json += F(",\"wifi_bytes_received\":");
  json += String(wifiBytesReceived);
  json += F(",\"uart_bytes_forwarded\":");
  json += String(uartBytesForwarded);
  json += F(",\"dropped_bytes\":");
  json += String(droppedBytes);
  json += F(",\"tcp_connections\":");
  json += String(tcpConnections);
  json += F(",\"last_uart_data_age_ms\":");
  json += lastUartDataMs == 0 ? String(0) : String(millis() - lastUartDataMs);
  json += F(",\"free_heap\":");
  json += String(ESP.getFreeHeap());
  json += F(",\"uptime_ms\":");
  json += String(millis());
  json += '}';
  return json;
}

String configJson() {
  String json;
  json.reserve(300);
  json += F("{\"ssid\":");
  appendJsonString(json, bridgeConfig.ssid);
  json += F(",\"password_set\":");
  json += bridgeConfig.password.length() > 0 ? F("true") : F("false");
  json += F(",\"jetson_host\":");
  appendJsonString(json, bridgeConfig.jetsonHost);
  json += F(",\"jetson_port\":");
  json += String(bridgeConfig.jetsonPort);
  json += F(",\"device_hostname\":");
  appendJsonString(json, DEVICE_HOSTNAME);
  json += '}';
  return json;
}

void redirectToPortal() {
  webServer.sendHeader("Location", "http://192.168.4.1/", true);
  webServer.send(302, "text/plain", "");
}

void registerWebRoutes() {
  webServer.on("/", HTTP_GET, []() {
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send_P(200, "text/html; charset=utf-8", kConfigUi);
  });

  webServer.on("/api/status", HTTP_GET,
               []() { webServer.send(200, "application/json", statusJson()); });
  webServer.on("/health", HTTP_GET,
               []() { webServer.send(200, "application/json", statusJson()); });
  webServer.on("/api/config", HTTP_GET,
               []() { webServer.send(200, "application/json", configJson()); });

  webServer.on("/api/scan", HTTP_GET, []() {
    const int count = WiFi.scanNetworks(false, true);
    String json = F("{\"networks\":[");
    for (int i = 0; i < count; ++i) {
      if (i) {
        json += ',';
      }
      json += F("{\"ssid\":");
      appendJsonString(json, WiFi.SSID(i));
      json += F(",\"rssi\":");
      json += String(WiFi.RSSI(i));
      json += F(",\"channel\":");
      json += String(WiFi.channel(i));
      json += F(",\"secure\":");
      json += WiFi.encryptionType(i) == ENC_TYPE_NONE ? F("false") : F("true");
      json += '}';
    }
    json += F("]}");
    WiFi.scanDelete();
    webServer.send(200, "application/json", json);
  });

  webServer.on("/api/save", HTTP_POST, []() {
    BridgeConfig next = bridgeConfig;
    const String requestedSsid = webServer.arg("ssid");
    const String requestedPassword = webServer.arg("password");
    const String requestedHost = webServer.arg("jetson_host");
    const long requestedPort = webServer.arg("jetson_port").toInt();

    next.ssid = requestedSsid;
    next.ssid.trim();
    next.jetsonHost = requestedHost;
    next.jetsonHost.trim();
    if (requestedPassword.length() > 0 || next.ssid != bridgeConfig.ssid) {
      next.password = requestedPassword;
    }
    if (requestedPort < 1 || requestedPort > 65535) {
      sendJsonError(400, F("??? 1~65535 ???? ???."));
      return;
    }
    next.jetsonPort = static_cast<uint16_t>(requestedPort);

    if (next.ssid.length() == 0 || next.ssid.length() > 32) {
      sendJsonError(400, F("SSID? 1~32??? ???."));
      return;
    }
    if (next.password.length() > 0 &&
        (next.password.length() < 8 || next.password.length() > 63)) {
      sendJsonError(400, F("Wi-Fi ????? 8~63??? ???."));
      return;
    }
    if (next.jetsonHost.length() == 0 || next.jetsonHost.length() > 63) {
      sendJsonError(400, F("Jetson ???? ?????."));
      return;
    }
    if (!configStore.save(next)) {
      sendJsonError(500, F("??? ???? ???? ?????."));
      return;
    }

    bridgeConfig = next;
    webServer.send(200, "application/json", "{\"ok\":true,\"rebooting\":true}");
    scheduleReboot();
  });

  webServer.on("/api/restart", HTTP_POST, []() {
    webServer.send(200, "application/json", "{\"ok\":true}");
    scheduleReboot(700);
  });

  webServer.on("/api/reset", HTTP_POST, []() {
    if (!configStore.clear()) {
      sendJsonError(500, F("??? ????? ?????."));
      return;
    }
    webServer.send(200, "application/json", "{\"ok\":true,\"rebooting\":true}");
    scheduleReboot();
  });

  webServer.on("/generate_204", HTTP_ANY, redirectToPortal);
  webServer.on("/hotspot-detect.html", HTTP_ANY, redirectToPortal);
  webServer.on("/connecttest.txt", HTTP_ANY, redirectToPortal);
  webServer.on("/ncsi.txt", HTTP_ANY, redirectToPortal);
  webServer.on("/fwlink", HTTP_ANY, redirectToPortal);
  webServer.onNotFound([]() {
    if (portalActive) {
      redirectToPortal();
    } else {
      sendJsonError(404, F("Not found"));
    }
  });
}

void startNetworkServices() {
  if (networkServicesStarted || WiFi.status() != WL_CONNECTED) {
    return;
  }

  ArduinoOTA.setHostname(DEVICE_HOSTNAME);
  if (strlen(OTA_PASSWORD) > 0 &&
      strcmp(OTA_PASSWORD, "CHANGE_THIS_OTA_PASSWORD") != 0) {
    ArduinoOTA.setPassword(OTA_PASSWORD);
  }
  ArduinoOTA.onStart([]() { videoClient.stop(); });
  ArduinoOTA.begin();

  if (MDNS.begin(DEVICE_HOSTNAME)) {
    MDNS.addService("http", "tcp", 80);
    MDNS.addService("arduino", "tcp", 8266);
  }
  networkServicesStarted = true;
}

void connectJetson() {
  const uint32_t now = millis();
  if (WiFi.status() != WL_CONNECTED) {
    videoClient.stop();
    return;
  }
  if (videoClient.connected() || now - lastTcpAttemptMs < kTcpRetryMs) {
    return;
  }

  lastTcpAttemptMs = now;
  videoClient.stop();
  if (videoClient.connect(bridgeConfig.jetsonHost.c_str(),
                          bridgeConfig.jetsonPort)) {
    videoClient.setNoDelay(true);
    videoClient.keepAlive(5, 3, 3);
    ++tcpConnections;
  }
}

void drainOrForwardSerial() {
  const int available = Serial.available();
  if (available <= 0) {
    return;
  }

  const size_t availableBytes = static_cast<size_t>(available);
  const size_t requested =
      availableBytes < sizeof(copyBuffer) ? availableBytes : sizeof(copyBuffer);
  const size_t count = Serial.readBytes(copyBuffer, requested);
  if (count == 0) {
    return;
  }

  uartBytesReceived += count;
  lastUartDataMs = millis();
  if (!videoClient.connected()) {
    droppedBytes += count;
    return;
  }

  size_t sent = 0;
  while (sent < count && videoClient.connected()) {
    const size_t written = videoClient.write(copyBuffer + sent, count - sent);
    if (written == 0) {
      videoClient.stop();
      break;
    }
    sent += written;
    wifiBytesForwarded += written;
    yield();
  }
  if (sent < count) {
    droppedBytes += count - sent;
  }
}

void forwardTcpToSerial() {
  if (!videoClient.connected()) {
    return;
  }

  while (videoClient.available() > 0) {
    const size_t availableBytes = static_cast<size_t>(videoClient.available());
    const size_t requested =
        availableBytes < sizeof(copyBuffer) ? availableBytes : sizeof(copyBuffer);
    const int count = videoClient.read(copyBuffer, requested);
    if (count <= 0) {
      break;
    }

    wifiBytesReceived += static_cast<uint32_t>(count);
    const size_t written = Serial.write(copyBuffer, static_cast<size_t>(count));
    uartBytesForwarded += written;
    if (written < static_cast<size_t>(count)) {
      droppedBytes += static_cast<size_t>(count) - written;
    }
    yield();
  }
}
void updateNetworkState() {
  if (WiFi.status() == WL_CONNECTED) {
    disconnectedSinceMs = 0;
    startNetworkServices();
    connectJetson();
    if (portalActive && millis() - portalStartedMs >= kBootPortalWindowMs) {
      stopSetupPortal();
    }
    return;
  }

  videoClient.stop();
  if (disconnectedSinceMs == 0) {
    disconnectedSinceMs = millis();
  }
  if (!portalActive && millis() - disconnectedSinceMs >= kWifiPortalDelayMs) {
    startSetupPortal();
  }
}

void updateStatusLed() {
  const uint32_t now = millis();
  if (videoClient.connected()) {
    setLed(true);
  } else if (WiFi.status() == WL_CONNECTED) {
    setLed((now / 250U) % 2U == 0U);
  } else if (portalActive) {
    setLed((now / 500U) % 2U == 0U);
  } else {
    setLed((now / 1000U) % 2U == 0U);
  }
}

void emitDiagnostics() {
  const uint32_t now = millis();
  if (now - lastDiagnosticsMs < 5000U) {
    return;
  }
  lastDiagnosticsMs = now;
  const String apIp = WiFi.softAPIP().toString();
  const String stationIp = WiFi.localIP().toString();
  Serial.printf("\n#IOTCAM wifi=%u ap=%u ap_ip=%s sta_ip=%s tcp=%u\n",
                WiFi.status() == WL_CONNECTED, portalActive,
                apIp.c_str(), stationIp.c_str(), videoClient.connected());
}

}  // namespace

void setup() {
  pinMode(kStatusLed, OUTPUT);
  setLed(false);

  Serial.setDebugOutput(false);
  Serial.setRxBufferSize(kSerialRxBuffer);
  Serial.begin(kP4Baud, SERIAL_8N1, SERIAL_FULL);
#ifndef DT06_BUILD
  // ESP-12N swapped UART0: RX=GPIO13, TX=GPIO15.
  Serial.swap();
#endif
  Serial.setTimeout(2);

  configStore.begin();
  BridgeConfig fallback;
  fallback.ssid = WIFI_SSID;
  fallback.password = WIFI_PASSWORD;
  fallback.jetsonHost = JETSON_HOST;
  fallback.jetsonPort = JETSON_PORT;
  bridgeConfig = configStore.load(fallback);

  registerWebRoutes();
  webServer.begin();
  startStation();
  startSetupPortal();
}

void loop() {
  drainOrForwardSerial();
  updateNetworkState();
  forwardTcpToSerial();

  if (portalActive) {
    dnsServer.processNextRequest();
  }
  webServer.handleClient();
  if (networkServicesStarted) {
    ArduinoOTA.handle();
    MDNS.update();
  }

  updateStatusLed();
#ifndef DT06_BUILD
  emitDiagnostics();
#endif
  if (rebootAtMs != 0 && static_cast<int32_t>(millis() - rebootAtMs) >= 0) {
    delay(30);
    ESP.restart();
  }
  yield();
}
