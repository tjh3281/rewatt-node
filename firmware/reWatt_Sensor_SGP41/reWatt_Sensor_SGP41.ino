#include <Arduino.h>
#include <SensirionI2cSht3x.h>
#include <SensirionI2CSgp41.h>
#include <VOCGasIndexAlgorithm.h>
#include <NOxGasIndexAlgorithm.h>
#include <Wire.h>
#include "LD2412.h"

// --- Hardware Pins ---
// SHT30 (0x44) + SGP41 (0x59) share the I2C bus
#define I2C_SDA 22
#define I2C_SCL 23 

// LD2412 Hardware Serial1 Pins
#define RX_PIN D7 
#define TX_PIN D6 
#define LD_BAUD 115200 

// --- Sensor Objects ---
SensirionI2cSht3x sensor;
SensirionI2CSgp41 sgp41;
VOCGasIndexAlgorithm vocAlgorithm;
NOxGasIndexAlgorithm noxAlgorithm;
LD2412 radar(Serial1);

// --- Non-Blocking Timers ---
unsigned long lastShtRead = 0;
unsigned long lastSerialPrint = 0;
const unsigned long SHT_INTERVAL = 1000;  // Read temp/hum/gas every 1 second (gas index algorithm expects 1 s)
const unsigned long PRINT_INTERVAL = 1000; // Output JSON to phone every 1 second

// --- State Variables ---
float currentTemp = 0.0;
float currentHum = 0.0;

// SGP41 compensation ticks, default 50%RH / 25C until the SHT30 gives a reading
uint16_t rhTicks = 0x8000;
uint16_t tTicks = 0x6666;
uint8_t conditioningLeft = 10; // NOx needs 10 s of conditioning after power-up
int32_t vocIndex = 0;
int32_t noxIndex = 0;
// Raw SGP41 signals (ticks), sent for debugging: shows whether the sensor itself reacts even when
// the Gas Index stays flat. The app ignores these fields.
uint16_t lastSrawVoc = 0;
uint16_t lastSrawNox = 0;

int currentRadarState = 0;
int movDist = 0;
int movEnergy = 0;
int statDist = 0;
int statEnergy = 0;

void setup() {
    // 1. Start USB Serial for Android OTG
    Serial.begin(115200);
    while (!Serial) delay(100);

    // 2. Initialize SHT30
    Wire.begin(I2C_SDA, I2C_SCL);
    sensor.begin(Wire, SHT30_I2C_ADDR_44);
    sensor.stopMeasurement();
    delay(50);
    sensor.softReset();
    delay(200); // Give it time to wake up
    sgp41.begin(Wire);

    // 3. Initialize LD2412 Radar
    Serial1.begin(LD_BAUD, SERIAL_8N1, RX_PIN, TX_PIN); 
    radar.readFirmwareVersion(); // Initial test request to wake connection
}

void loop() {
    unsigned long currentMillis = millis();

    // --- Task 1: Read SHT30 + SGP41 (Every 1 Second) ---
    if (currentMillis - lastShtRead >= SHT_INTERVAL) {
        lastShtRead = currentMillis;
        float t = 0.0, h = 0.0;
        
        // We use measureSingleShot for better reliability on XIAO
        if (sensor.measureSingleShot(REPEATABILITY_HIGH, false, t, h) == NO_ERROR) {
            currentTemp = t;
            currentHum = h;
            rhTicks = h * 65535.0f / 100.0f;
            tTicks = (t + 45.0f) * 65535.0f / 175.0f;
        }

        uint16_t srawVoc = 0, srawNox = 0;
        uint16_t err = conditioningLeft
            ? sgp41.executeConditioning(rhTicks, tTicks, srawVoc)
            : sgp41.measureRawSignals(rhTicks, tTicks, srawVoc, srawNox);

        if (err) {
            vocIndex = noxIndex = -1; // -1 = SGP41 not responding
        } else if (conditioningLeft) {
            conditioningLeft--;
        } else {
            lastSrawVoc = srawVoc;
            lastSrawNox = srawNox;
            vocIndex = vocAlgorithm.process(srawVoc);
            noxIndex = noxAlgorithm.process(srawNox);
        }
    }

    // --- Task 2: Read LD2412 (Continuously) ---
    int state = radar.targetState();
    
    // If -1 is returned, reading failed or no new data is available yet
    if (state != -1) {
        currentRadarState = state;
        
        // Update distance/energy based on target state (0=None, 1=Mov, 2=Stat, 3=Both)
        movDist = (state == 1 || state == 3) ? radar.movingDistance() : 0;
        movEnergy = (state == 1 || state == 3) ? radar.movingEnergy() : 0;
        
        statDist = (state == 2 || state == 3) ? radar.staticDistance() : 0;
        statEnergy = (state == 2 || state == 3) ? radar.staticEnergy() : 0;
    }

    // --- Task 3: Print Unified JSON (Every 1 Second) ---
    if (currentMillis - lastSerialPrint >= PRINT_INTERVAL) {
        lastSerialPrint = currentMillis;

        // Construct a minified JSON string to save serial bandwidth
        Serial.print("{\"temp\":");
        Serial.print(currentTemp, 2);
        Serial.print(",\"hum\":");
        Serial.print(currentHum, 2);
        Serial.print(",\"voc\":");
        Serial.print(vocIndex);
        Serial.print(",\"nox\":");
        Serial.print(noxIndex);
        Serial.print(",\"sraw_voc\":");
        Serial.print(lastSrawVoc);
        Serial.print(",\"sraw_nox\":");
        Serial.print(lastSrawNox);
        Serial.print(",\"radar\":{\"state\":");
        Serial.print(currentRadarState);
        Serial.print(",\"mov_d\":");
        Serial.print(movDist);
        Serial.print(",\"mov_e\":");
        Serial.print(movEnergy);
        Serial.print(",\"stat_d\":");
        Serial.print(statDist);
        Serial.print(",\"stat_e\":");
        Serial.print(statEnergy);
        Serial.println("}}"); 
    }
}