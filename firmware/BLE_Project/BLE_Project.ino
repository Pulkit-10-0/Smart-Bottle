#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// OLED CONFIG
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define I2C_SDA 21
#define I2C_SCL 22

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

//  BLE CONFIG
BLECharacteristic *pCharacteristic;
BLECharacteristic *pTimeSyncCharacteristic;
bool deviceConnected = false;
unsigned long syncedTime = 0;
unsigned long lastSyncMillis = 0;

#define SERVICE_UUID        "12345678-1234-1234-1234-123456789abc"
#define CHARACTERISTIC_UUID "abcd1234-5678-1234-5678-123456789abc"
#define TIME_SYNC_UUID      "11223344-5566-7788-99aa-bbccddeeff00"

// BLE 
class TimeSyncCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) override {
    std::string value = pCharacteristic->getValue();
    if (value.length() > 0) {
      syncedTime = atol(value.c_str());
      lastSyncMillis = millis();
      Serial.print("Time synced from phone: ");
      Serial.println(syncedTime);
    }
  }
};

class MyServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override { 
    deviceConnected = true; 
    Serial.println("Client connected!");
  }
  void onDisconnect(BLEServer* pServer) override { 
    deviceConnected = false; 
    Serial.println("Client disconnected!");
    BLEDevice::startAdvertising();
    Serial.println("Advertising restarted after disconnect");
  }
};

// Time
unsigned long getCurrentTime() {
  if (syncedTime == 0) return 0;
  unsigned long elapsedSeconds = (millis() - lastSyncMillis) / 1000;
  return syncedTime + elapsedSeconds;
}

void formatTimestamp(char* buffer, size_t bufferSize) {
  unsigned long currentTime = getCurrentTime();
  if (currentTime == 0) {
    snprintf(buffer, bufferSize, "Not synced");
    return;
  }
  time_t rawtime = currentTime;
  struct tm *timeinfo = localtime(&rawtime);
  strftime(buffer, bufferSize, "%Y-%m-%d %H:%M:%S", timeinfo);
}


void setup() {
  Serial.begin(115200);
  delay(1000);

  // Timezone
  setenv("TZ", "IST-5:30", 1);
  tzset();

  // OLED INIT
  Wire.begin(I2C_SDA, I2C_SCL);
  if(!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println(F("SSD1306 allocation failed"));
    for(;;);
  }
  display.clearDisplay();
  display.setTextSize(2);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0,0);
  display.println("OLED Initialized");
  display.display();
  delay(1000);

  // BLE INIT
  BLEDevice::init("Smart Bottle");
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pCharacteristic->addDescriptor(new BLE2902());

  pTimeSyncCharacteristic = pService->createCharacteristic(
                              TIME_SYNC_UUID,
                              BLECharacteristic::PROPERTY_WRITE
                            );
  pTimeSyncCharacteristic->setCallbacks(new TimeSyncCallbacks());

  pService->start();
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->start();

  Serial.println("Waiting for a client...");
}

// main loop
void loop() {
  float temp = random(200, 400) / 10.0;
  int uvCycle = random(0, 2); // Example: 0 = off, 1 = on
  float battery = random(370, 420) / 100.0; // Example: 3.7V to 4.2V
  float flow = random(0, 100) / 10.0; // Example: 0.0 to 10.0 L/min

  char ts[25];
  formatTimestamp(ts, sizeof(ts));

  if (deviceConnected) {
    StaticJsonDocument<256> doc;
    doc["t"] = temp;
    doc["uv"] = uvCycle;
    doc["bat"] = battery;
    doc["flow"] = flow;
    doc["ts"] = ts;

    String jsonStr;
    serializeJson(doc, jsonStr);
    jsonStr += "\n";

    pCharacteristic->setValue(jsonStr.c_str());
    pCharacteristic->notify();

    Serial.println("Sent: " + jsonStr);
  }

  // OLED DISPLAY
  display.clearDisplay();
  display.setTextSize(1);
  display.setCursor(0,0);
  display.print("Temp: ");
  display.print(temp);
  display.println(" C");

  display.setCursor(0, 12);
  display.print("UV: ");
  display.println(uvCycle ? "ON" : "OFF");

  display.setCursor(0, 24);
  display.print("Bat: ");
  display.print(battery);
  display.println(" V");

  display.setCursor(0, 36);
  display.print("Flow: ");
  display.print(flow);
  display.println(" L/m");

  display.setCursor(0, 48);
  display.println(ts);

  display.setCursor(0, 56);
  if (deviceConnected) display.println("BLE: Connected");
  else display.println("BLE: Waiting...");

  display.display();

  delay(5000);
}
