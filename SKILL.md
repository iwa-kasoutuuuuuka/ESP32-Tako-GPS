---
name: esp32-multi-logger-development
description: ESP32を用いたバイク/車用多機能データロガー（タコメーター、GPS、SDカード、Bluetooth）のファームウェアおよびAndroid連携アプリの開発・保守を行うための専門知識とコーディングルール。
---

# ESP32 バイク/車用 多機能データロガー 開発スキル

本ドキュメントは、ESP32をベースとしたバイク・自動車用多機能データロガー（タコメーター、GPS、SDカード保存、Bluetooth通信）のファームウェア（Arduino/ESP-IDF）およびAndroid連携アプリの開発・保守を行うための開発ルール、設計思想、ピンアサイン、および実装テンプレートを定義するものである。

---

## 1. 開発者スタンス & 進め方
* **バイブコーディング（Vibe Coding）**:
  ユーザーが実装したい機能や発生したエラーをラフに伝え、AI側がその意図を汲み取って即座にコンパイル・実行可能な高クオリティなコードや具体的なアドバイスを提供する。
* **ハードウェア確定済み**:
  物理配線、電圧対策（5V/3.3V混在回路の保護など）はすべて完了している。AIはハードウェアの再設計を提案せず、**ソフトウェア（スケッチ）の実装に100%集中**すること。

---

## 2. システム基本仕様
1. **タコメーター**:
   * プラグコード巻付けによるパルス誘導式。
   * PC817フォトカプラモジュールで高電圧・ノイズを物理的に絶縁・保護。
2. **速度・位置測定**:
   * GPS受信機「6Y-GPSU3-NEO」（u-blox NEO-6M搭載）による計測。
3. **ローカル保存**:
   * MicroSDカードモジュールを使用したSPI通信によるCSVロギング（最大32GB/FAT32対応）。
4. **スマホ連携**:
   * ESP32内蔵のClassic Bluetooth（Bluetooth Serial）によるスマホへのリアルタイムデータ転送。

---

## 3. ピンアサイン（固定仕様・変更不可）
拡張ボードのジャンパピンは「5V」に設定済み。

| デバイス | ピン名 | ESP32 GPIO | 電源仕様 | 備考 |
| :--- | :--- | :--- | :--- | :--- |
| **タコメーター (PC817)** | OUT | **GPIO 13** | 独立 3.3V 給電 | 外部割り込み使用。5V混入による破損防止のためVCCは3.3V固定。 |
| **GPS (NEO-6M)** | TX | **GPIO 16** | 5V 給電 | ESP32のRX2に接続 (HardwareSerial2) |
| | RX | **GPIO 17** | 5V 給電 | ESP32のTX2に接続 (HardwareSerial2) |
| **MicroSDカード** | MISO | **GPIO 19** | 5V 給電 | VSPIを使用。モジュール内レギュレータによる電圧降下防止のため5V必須。 |
| | MOSI | **GPIO 23** | | |
| | SCK | **GPIO 18** | | |
| | CS | **GPIO 5** | | |

---

## 4. ファームウェア（ESP32）コーディング設計ルール

### ① マルチコア（Dual Core）の徹底活用
FreeRTOSタスクを用いて、通信やSDカード書き込み処理（I/Oブロック）がタコメーターのパルス検知（ミリ秒単位の割り込み処理）に干渉しないようにすること。

* **Core 1 (メイン/計測・解析タスク)**:
  * **パルスカウント**: GPIO 13の割り込み（`FALLING`）によるパルス検出。
  * **デバウンス対策**: 点火サージノイズ（チャタリング）による誤検知を防ぐため、割り込みハンドラ（ISR）内に簡易デバウンス処理（例：3msの不感時間）を実装する。
  * **GPSデータ受信**: HardwareSerial2を介した `TinyGPS++` 等によるデータ読み取りとパース。
* **Core 0 (サブ/通信・保存タスク)**:
  * **SDカード保存**: CSV形式でのファイル追記処理（バッファリング推奨）。
  * **Bluetooth送信**: `BluetoothSerial` を用いたスマホアプリへのリアルタイム送信。
* **コア間連携**:
  * データ整合性のため、共有データには `volatile` 指定を行い、適切なセマフォ（`SemaphoreHandle_t`）やクリティカルセクション（`portMUX_TYPE`）による排他制御を行うこと。

### ② データ通信仕様
* **デバイス名**: Bluetoothデバイス名は `ESP32_Logger` とする。
* **データ周期**: スマホへのCSV送信は **10Hz (100ms周期)** で定期実行する。
* **GPSデータのキャッシュ**:
  GPSデータが1Hz（1秒周期）でしか更新されない間も、直前のGPSキャッシュデータ（緯度・経度・速度・時刻）を保持し、最新のRPMと共に10Hzで継続して送信する。
* **ローローパスフィルタ**:
  RPM表示がスマホ側でガタつくのを防止するため、ESP32側で直近4回分の移動平均（Simple Moving Average）を適用したRPMを算出して送信する。

### ③ RPM補正設計（生データ主義）
* **車種固有の補正値は持たない**:
  ESP32側は極力「生パルス情報（パルス間隔：pulse interval、または生RPM）」の送信に留める。
* **最終換算はAndroid側**:
  後述の `pulsePerRevolution` を用いた最終RPM換算はスマホアプリ側で行う。

---

## 5. 通信データフォーマット (CSV形式)
Bluetooth Serialで送信されるデータは、以下のフォーマットに統一する。

```csv
[RPM],[速度],[緯度],[経度],[時刻]\n
```

* **RPM**: 直近4回の移動平均を適用した計算上のRPM（またはパルス間隔情報）
* **速度**: km/h単位の速度（小数点以下対応）
* **緯度 / 経度**: 小数点6桁以上のGPS座標
* **時刻**: UTCまたはJSTの時刻文字列（例: `HHMMSScc` または ISO表記形式）

---

## 6. Androidアプリ設計要件

* **パルス設定の保持**:
  `pulsePerRevolution`（1回転あたりのパルス数）を `Jetpack DataStore` 等を用いてローカルに永続化保存する。
* **即時再計算**:
  設定UIでパルス数が変更された際、ESP32側の再起動を必要とせず、受信した生データを基にAndroidアプリ側で即座にRPM計算を修正・反映する。
* **自動再接続**:
  Bluetooth接続が切断された場合でも、設定を維持したまま自動再接続を試みるロジックを組み込む。

### プリセット設定値一覧
* **2ストローク単気筒**: `1.0` pulse / revolution
* **4ストローク単気筒**: `0.5` pulse / revolution
* **将来の拡張性**:
  2気筒、4気筒、wasted spark（同時点火）、CDI/TCIの点火方式差異、および任意の `pulsePerRevolution` 設定に対応可能な柔軟なパースロジックにすること。

---

## 7. 実装コードテンプレート例 (ESP32ファームウェア)

以下に、上記の設計ルールを全て満たしたESP32側のArduinoスケッチの基本構造を示す。

```cpp
#include "BluetoothSerial.h"
#include <TinyGPS++.h>
#include <SPI.h>
#include <SD.h>

// ピンアサイン定義
#define TACHO_PIN    13
#define GPS_RX_PIN   16
#define GPS_TX_PIN   17
#define SD_CS_PIN    5

// デバウンス時間 (ミリ秒)
const unsigned long DEBOUNCE_TIME_MS = 3;

// Bluetooth設定
BluetoothSerial SerialBT;

// GPS設定
TinyGPSPlus gps;
HardwareSerial gpsSerial(2); // HardwareSerial2

// 共有データ構造体
struct LoggerData {
  volatile float rpm;
  volatile float speed;
  volatile double latitude;
  volatile double longitude;
  char timeStr[12];
};

LoggerData sharedData;
portMUX_TYPE dataMux = vSemaphoreCreateBinary() ? spinlock_initialize() : portMUX_INITIALIZER_UNLOCKED; // 排他制御用
SemaphoreHandle_t dataMutex;

// タコメーター用変数
volatile unsigned long lastPulseTime = 0;
volatile unsigned long pulseInterval = 0;
volatile float rpmBuffer[4] = {0, 0, 0, 0};
volatile int rpmBufferIndex = 0;

// 外部割り込みサービスルーチン (Core 1で実行される割り込み)
void IRAM_ATTR handleTachoInterrupt() {
  unsigned long now = millis();
  if (now - lastPulseTime > DEBOUNCE_TIME_MS) {
    pulseInterval = now - lastPulseTime;
    lastPulseTime = now;
    
    // 生RPM計算 (1パルスあたりの時間から計算)
    if (pulseInterval > 0) {
      float rawRpm = 60000.0 / pulseInterval;
      
      // 直近4回の移動平均
      rpmBuffer[rpmBufferIndex] = rawRpm;
      rpmBufferIndex = (rpmBufferIndex + 1) % 4;
      
      float sum = 0;
      for (int i = 0; i < 4; i++) {
        sum += rpmBuffer[i];
      }
      
      portENTER_CRITICAL_ISR(&dataMux);
      sharedData.rpm = sum / 4.0;
      portEXIT_CRITICAL_ISR(&dataMux);
    }
  }
}

// Core 0 タスク: SD保存およびBluetooth転送 (10Hz)
void core0Task(void* parameter) {
  TickType_t lastWakeTime = xTaskGetTickCount();
  const TickType_t frequency = pdMS_TO_TICKS(100); // 100ms (10Hz)
  
  for (;;) {
    LoggerData localCopy;
    
    // データの安全なコピー
    xSemaphoreTake(dataMutex, portMAX_DELAY);
    localCopy = sharedData;
    xSemaphoreGive(dataMutex);
    
    // CSV文字列作成
    String csvLine = String(localCopy.rpm, 1) + "," +
                     String(localCopy.speed, 2) + "," +
                     String(localCopy.latitude, 6) + "," +
                     String(localCopy.longitude, 6) + "," +
                     String(localCopy.timeStr) + "\n";
                     
    // Bluetooth送信
    if (SerialBT.connected()) {
      SerialBT.print(csvLine);
    }
    
    // SDカード書き込み
    File file = SD.open("/log.csv", FILE_WRITE);
    if (file) {
      file.print(csvLine);
      file.close();
    }
    
    vTaskDelayUntil(&lastWakeTime, frequency);
  }
}

void setup() {
  Serial.begin(115200);
  
  // ミューテックス初期化
  dataMutex = xSemaphoreCreateMutex();
  
  // GPSシリアル初期化
  gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  
  // Bluetooth初期化
  if (!SerialBT.begin("ESP32_Logger")) {
    Serial.println("Bluetoothの初期化に失敗しました。");
  } else {
    Serial.println("Bluetoothデバイス 「ESP32_Logger」 起動完了");
  }
  
  // SDカード初期化 (VSPI)
  SPI.begin(18, 19, 23, SD_CS_PIN);
  if (!SD.begin(SD_CS_PIN)) {
    Serial.println("SDカードの初期化に失敗しました。");
  } else {
    Serial.println("SDカード初期化成功");
  }
  
  // タコメーター入力ピン設定 & 割り込み設定
  pinMode(TACHO_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(TACHO_PIN), handleTachoInterrupt, FALLING);
  
  // Core 0 で通信・保存タスクを起動 (優先度 1)
  xTaskCreatePinnedToCore(
    core0Task,
    "Core0_CommSave",
    4096,
    NULL,
    1,
    NULL,
    0
  );
}

void loop() {
  // Core 1 (Arduinoメインループ): GPSデータの読み込みとパース
  while (gpsSerial.available() > 0) {
    char c = gpsSerial.read();
    if (gps.encode(c)) {
      xSemaphoreTake(dataMutex, portMAX_DELAY);
      if (gps.speed.isValid()) sharedData.speed = gps.speed.kmph();
      if (gps.location.isValid()) {
        sharedData.latitude = gps.location.lat();
        sharedData.longitude = gps.location.lng();
      }
      if (gps.time.isValid()) {
        snprintf(sharedData.timeStr, sizeof(sharedData.timeStr), 
                 "%02d%02d%02d%02d", 
                 gps.time.hour(), gps.time.minute(), gps.time.second(), gps.time.centisecond());
      }
      xSemaphoreGive(dataMutex);
    }
  }
  
  // 割り込み漏れを防ぐため、Core 1では極力無駄なブロッキングを行わない
  delay(1);
}
```

---

## 8. トラブルシューティング & 実装チェックリスト
- [ ] PC817のVCCは必ずESP32の3.3Vから給電しているか？（5Vは厳禁）
- [ ] SDカードのCSピンはGPIO 5、VSPI用のピン（18, 19, 23）が正しく配線されているか？
- [ ] Bluetoothデバイス名が `ESP32_Logger` になっているか？
- [ ] Core 0のタスクスタックサイズ（4096バイト）がSDカードとBluetoothの同時動作時にスタックオーバーフローを起こしていないか？
- [ ] Androidアプリ側で、DataStoreに保存した `pulsePerRevolution` の変更が即時RPMに掛け合わされているか？
