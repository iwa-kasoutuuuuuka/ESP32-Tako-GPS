#include <SPI.h>
#include <SD.h>
#include "BluetoothSerial.h"
#include <TinyGPS++.h>

// ==========================================
// 1. ピン配置および定数の定義
// ==========================================
#define TACHO_PIN        13   // タコメーターパルス入力 (GPIO 13)
#define GPS_RX_PIN       16   // HardwareSerial2 RX
#define GPS_TX_PIN       17   // HardwareSerial2 TX
#define SD_CS_PIN        5    // VSPI CS
#define SD_SCK_PIN       18   // VSPI SCK
#define SD_MISO_PIN      19   // VSPI MISO
#define SD_MOSI_PIN      23   // VSPI MOSI

// デバウンス時間 (3ms = 3000マイクロ秒)
const unsigned long DEBOUNCE_TIME_US = 3000;
// パルス未検出判定のタイムアウト時間 (2秒 = 2,000,000マイクロ秒)
const unsigned long TACHO_TIMEOUT_US = 2000000;

// ==========================================
// 2. オブジェクト・グローバル変数の宣言
// ==========================================
BluetoothSerial SerialBT;
TinyGPSPlus gps;
HardwareSerial gpsSerial(2); // HardwareSerial2を使用

// SDカードが正常に初期化されたかどうかの管理フラグ
bool sdInitialized = false;

// コア間で安全にデータを共有するための構造体
struct SensorData {
  volatile unsigned long pulse_us; // パルス周期（マイクロ秒）
  volatile float speed;            // GPS速度（km/h）
  volatile double latitude;        // GPS緯度
  volatile double longitude;       // GPS経度
  char utc_time[12];               // UTC時刻 (HH:MM:SS)
  volatile int satellites;         // 捕捉している衛星数
};

SensorData sharedData;
SemaphoreHandle_t dataMutex; // 排他制御用のミューテックス

// タコメーター計測用の変数 (ISRおよびCore 1で使用)
volatile unsigned long lastPulseMicros = 0;
volatile unsigned long latestIntervalUs = 0;

// ==========================================
// 3. 外部割り込みサービスルーチン (ISR)
// ==========================================
// ※極限まで処理を減らし、割り込み処理の遅延を抑止します
void IRAM_ATTR handleTachoInterrupt() {
  unsigned long now = micros();
  unsigned long interval = now - lastPulseMicros;
  
  // チャタリング防止デバウンス（3ms以内の割り込みは無視）
  if (interval >= DEBOUNCE_TIME_US) {
    latestIntervalUs = interval;
    lastPulseMicros = now;
  }
}

// ==========================================
// 4. ヘルパー関数定義 (NMEAチェックサムおよびA-GPS処理)
// ==========================================
byte calculateChecksum(String str) {
  byte checksum = 0;
  for (int i = 0; i < str.length(); i++) {
    checksum ^= str.charAt(i);
  }
  return checksum;
}

// 10進数座標（DD.dddddd）からNMEA度分形式（DDMM.mmmmm）へ変換する
String convertToNmeaAngle(double decimalDegree, bool isLongitude) {
  double absVal = fabs(decimalDegree);
  int degrees = (int)absVal;
  double minutes = (absVal - degrees) * 60.0;
  
  char buffer[32];
  if (isLongitude) {
    snprintf(buffer, sizeof(buffer), "%03d%02.5f", degrees, minutes);
  } else {
    snprintf(buffer, sizeof(buffer), "%02d%02.5f", degrees, minutes);
  }
  return String(buffer);
}

// アシストパケットの解析およびGPSモジュールへの注入
void processAgpsPacket(String packet) {
  // パケット例: $AGPS,35.681234,139.767123,50.4,290526,235130.00*4A
  int asteriskIdx = packet.indexOf('*');
  if (asteriskIdx < 0) return;
  
  String payload = packet.substring(1, asteriskIdx); // $を除いた部分
  String receivedChecksumStr = packet.substring(asteriskIdx + 1);
  receivedChecksumStr.trim();
  
  byte calculated = 0;
  for (int i = 0; i < payload.length(); i++) {
    calculated ^= payload.charAt(i);
  }
  
  byte received = strtol(receivedChecksumStr.c_str(), NULL, 16);
  if (calculated != received) {
    Serial.println("[AGPS] チェックサムエラー。パケットを破棄します。");
    return;
  }
  
  String values[6];
  int valCount = 0;
  int currentIdx = 0;
  
  int nextComma = payload.indexOf(',');
  if (nextComma < 0) return;
  currentIdx = nextComma + 1;
  
  while (currentIdx < payload.length() && valCount < 6) {
    nextComma = payload.indexOf(',', currentIdx);
    if (nextComma < 0) {
      values[valCount++] = payload.substring(currentIdx);
      break;
    }
    values[valCount++] = payload.substring(currentIdx, nextComma);
    currentIdx = nextComma + 1;
  }
  
  if (valCount < 5) {
    Serial.println("[AGPS] パケットのデータ数が不足しています。");
    return;
  }
  
  double lat = values[0].toDouble();
  double lon = values[1].toDouble();
  float alt = values[2].toFloat();
  String dateStr = values[3];
  String timeStr = values[4];
  
  String latDir = (lat >= 0) ? "N" : "S";
  String lonDir = (lon >= 0) ? "E" : "W";
  
  String nmeaLat = convertToNmeaAngle(lat, false);
  String nmeaLon = convertToNmeaAngle(lon, true);
  
  // ① u-blox 時刻初期化 ($PUBX,40)
  String pubx40_payload = "PUBX,40," + timeStr + "," + dateStr + ",0,0,0,0,0,10";
  byte cs40 = calculateChecksum(pubx40_payload);
  char cs40Str[8];
  sprintf(cs40Str, "*%02X", cs40);
  String pubx40 = "$" + pubx40_payload + String(cs40Str);
  
  // ② u-blox 位置初期化 ($PUBX,41)
  String pubx41_payload = "PUBX,41,0," + nmeaLat + "," + latDir + "," + nmeaLon + "," + lonDir + "," + String(alt, 1) + ",100,100";
  byte cs41 = calculateChecksum(pubx41_payload);
  char cs41Str[8];
  sprintf(cs41Str, "*%02X", cs41);
  String pubx41 = "$" + pubx41_payload + String(cs41Str);
  
  // GPSモジュールへ書き込み
  gpsSerial.println(pubx40);
  gpsSerial.println(pubx41);
  
  Serial.println("[AGPS] スマホからアシストデータを受信し、GPSモジュールへ注入しました！");
  Serial.print("  -> 時刻同期: "); Serial.println(pubx40);
  Serial.print("  -> 位置同期: "); Serial.println(pubx41);
}

// ==========================================
// 5. Core 0 タスク: 10Hz周期での通信およびSD保存
// ==========================================
void core0Task(void* parameter) {
  TickType_t lastWakeTime = xTaskGetTickCount();
  const TickType_t frequency = pdMS_TO_TICKS(100); // 100ms間隔 (10Hz)

  for (;;) {
    SensorData localCopy;

    // ミューテックスを用いてCore 1と衝突しないようにデータをローカルにコピー
    if (xSemaphoreTake(dataMutex, pdMS_TO_TICKS(50)) == pdTRUE) {
      localCopy = sharedData;
      xSemaphoreGive(dataMutex);
    } else {
      // ミューテックス取得に失敗した場合は、前回のデータを流用
      localCopy = sharedData;
    }

    // CSV文字列の生成 ([pulse_us],[速度],[緯度],[経度],[UTC時刻])
    String csvPayload = String(localCopy.pulse_us) + "," +
                        String(localCopy.speed, 1) + "," +
                        String(localCopy.latitude, 6) + "," +
                        String(localCopy.longitude, 6) + "," +
                        String(localCopy.utc_time);

    // チェックサムの計算
    byte checksum = calculateChecksum(csvPayload);
    char checksumStr[4];
    sprintf(checksumStr, "*%02X", checksum);
    
    // 最終CSV文字列の完成
    String finalCsv = csvPayload + String(checksumStr) + "\n";

    // シリアルモニターにデバッグ出力
    Serial.print("[Core0] 送信データ: ");
    Serial.print(finalCsv);

    // Bluetooth経由でスマホへリアルタイム送信
    if (SerialBT.connected()) {
      SerialBT.print(finalCsv);
    }

    // スマホからのA-GPSアシストデータの受信処理
    static String btBuffer = "";
    while (SerialBT.available() > 0) {
      char c = SerialBT.read();
      if (c == '\n') {
        if (btBuffer.startsWith("$AGPS")) {
          processAgpsPacket(btBuffer);
        }
        btBuffer = "";
      } else if (c != '\r') {
        btBuffer += c;
      }
    }

    // MicroSDカードへの追記保存 (10Hzでの頻繁な開閉を防ぐため、10回(1秒)分バッファリングして一気に書き込み)
    static String sdBuffer = "";
    static int sdBufferCount = 0;
    static bool isReserved = false;
    
    // ヒープメモリの動的再アロケーションによる断片化（メモリ不足クラッシュ）を完全に防止
    if (!isReserved) {
      sdBuffer.reserve(1024);
      isReserved = true;
    }
    
    sdBuffer += finalCsv;
    sdBufferCount++;
    
    if (sdBufferCount >= 10) {
      if (sdInitialized) {
        File logFile = SD.open("/log.txt", FILE_WRITE);
        if (logFile) {
          logFile.print(sdBuffer);
          logFile.close();
        } else {
          Serial.println("[Core0] SDカードへの書き込みに失敗しました。");
        }
      } else {
        // SDカードが利用できない場合はログを破棄し、シリアル警告（デバグ用）
        static unsigned long lastSdWarning = 0;
        if (millis() - lastSdWarning > 10000) { // 10秒に1回だけ警告出力
          Serial.println("[Core0] 警告: SDカードが初期化されていないため、ローカル保存をスキップします。");
          lastSdWarning = millis();
        }
      }
      sdBuffer = "";
      sdBufferCount = 0;
    }

    // 1秒間の高精度スリープ (Core 0を解放)
    vTaskDelayUntil(&lastWakeTime, frequency);

    // 5秒に1回 (50ループに1回) GPS診断情報を出力
    static int debugCounter = 0;
    if (++debugCounter >= 50) {
      debugCounter = 0;
      Serial.println("\n--- [GPS 診断デバッグ情報] ---");
      Serial.print("  処理した文字数 (charsProcessed): "); Serial.println(gps.charsProcessed());
      Serial.println("    ※この値が0のままで増えない場合、ESP32とGPSモジュール間の配線(TX/RXクロス接続)や電源に問題があります。");
      Serial.print("  チェックサムエラー数 (failedChecksum): "); Serial.println(gps.failedChecksum());
      Serial.println("    ※この値が処理文字数に対して非常に多い場合、通信速度(ボーレート)が一致していません。");
      Serial.print("  現在捕捉中の衛星数: "); Serial.println(localCopy.satellites);
      Serial.print("  緯度/経度有効判定: "); Serial.println(gps.location.isValid() ? "有効 (OK)" : "無効 (未測位)");
      if (gps.charsProcessed() > 0 && !gps.location.isValid()) {
        Serial.println("    💡 アドバイス: GPSからの電波信号は受信していますが、まだ衛星を捕捉（測位）できていません。屋内の場合は屋外に出て数分お待ちください。");
      }
      Serial.println("--------------------------------\n");
    }
  }
}

// ==========================================
// 6. メイン設定 (setup)
// ==========================================
void setup() {
  // デバッグ用シリアル通信の初期化
  Serial.begin(115200);
  delay(1000);
  Serial.println("====== ESP32 多機能データロガー 起動初期化 ======");

  // 排他制御用ミューテックスの生成
  dataMutex = xSemaphoreCreateMutex();
  if (dataMutex == NULL) {
    Serial.println("ミューテックスの作成に失敗しました。再起動します。");
    ESP.restart();
  }

  // 共有データの初期化
  sharedData.pulse_us = 0;
  sharedData.speed = 0.0;
  sharedData.latitude = 0.0;
  sharedData.longitude = 0.0;
  strcpy(sharedData.utc_time, "00:00:00");
  sharedData.satellites = 0;

  // GPSモジュールの通信開始 (HardwareSerial2)
  gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.println("GPSシリアル通信(HardwareSerial2)を起動しました。");

  // Bluetoothの起動
  if (!SerialBT.begin("ESP32_Logger")) {
    Serial.println("Bluetoothの初期化に失敗しました。再起動します。");
    ESP.restart();
  }
  Serial.println("Bluetoothデバイス 「ESP32_Logger」 起動完了");

  // SPI通信およびMicroSDカードの初期化
  SPI.begin(SD_SCK_PIN, SD_MISO_PIN, SD_MOSI_PIN, SD_CS_PIN);
  if (!SD.begin(SD_CS_PIN)) {
    Serial.println("SDカードの初期化に失敗しました。配線またはカードを確認してください。");
    sdInitialized = false;
  } else {
    Serial.println("SDカードの初期化に成功しました。(log.txt を使用)");
    sdInitialized = true;
  }

  // タコメーター入力ピンの設定および外部割り込み登録
  pinMode(TACHO_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(TACHO_PIN), handleTachoInterrupt, FALLING);
  Serial.println("タコメーター外部割り込み (GPIO 13, FALLING) を設定しました。");

  // Core 0 側で「通信・保存タスク」を起動 (優先度: 1, スタックサイズ: 4096バイト)
  xTaskCreatePinnedToCore(
    core0Task,      // タスク関数
    "Core0_Task",   // タスク名
    4096,           // スタックサイズ
    NULL,           // パラメータ
    1,              // 優先度
    NULL,           // タスクハンドル
    0               // 割り当てるコア (Core 0)
  );
  Serial.println("Core 0 に通信・保存タスクを割り当てました。");
  Serial.println("====== 起動初期化プロセス 完了 ======");
}

// ==========================================
// 7. メインループ (loop: Core 1で実行)
// ==========================================
void loop() {
  // --- GPSシリアルデータの読み込みと解析 ---
  while (gpsSerial.available() > 0) {
    char c = gpsSerial.read();
    gps.encode(c);
  }

  // --- タコメーターのタイムアウト（未検出）判定 ---
  unsigned long currentMicros = micros();
  unsigned long activeInterval = latestIntervalUs;

  // 最後にパルスを検知してから2秒以上経過している場合、エンジン停止とみなす
  if (currentMicros - lastPulseMicros > TACHO_TIMEOUT_US) {
    activeInterval = 0;
  }

  // --- 直近4回分のパルス周期の移動平均フィルタ (SMA) ---
  static unsigned long intervalBuffer[4] = {0, 0, 0, 0};
  static int bufferIndex = 0;
  static unsigned long lastProcessedPulseMicros = 0;
  unsigned long avgInterval = 0;

  if (activeInterval > 0) {
    // 新しいパルスを検知した時のみバッファを更新 (多重登録バグの防止)
    if (lastPulseMicros != lastProcessedPulseMicros) {
      lastProcessedPulseMicros = lastPulseMicros;
      intervalBuffer[bufferIndex] = activeInterval;
      bufferIndex = (bufferIndex + 1) % 4;
    }

    // バッファの平均を算出
    unsigned long sum = 0;
    int count = 0;
    for (int i = 0; i < 4; i++) {
      if (intervalBuffer[i] > 0) {
        sum += intervalBuffer[i];
        count++;
      }
    }
    avgInterval = (count > 0) ? (sum / count) : 0;
  } else {
    // タイムアウト時はバッファと追跡値をクリア
    for (int i = 0; i < 4; i++) {
      intervalBuffer[i] = 0;
    }
    lastProcessedPulseMicros = 0;
    avgInterval = 0;
  }

  // --- 解析データの共有用バッファへの安全な書き込み ---
  if (xSemaphoreTake(dataMutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    // フィルタ適用済みのパルス周期 (マイクロ秒)
    sharedData.pulse_us = avgInterval;

    // GPSから有効な速度情報が得られているか
    if (gps.speed.isValid()) {
      sharedData.speed = (float)gps.speed.kmph();
    }

    // GPSから有効な座標情報が得られているか
    if (gps.location.isValid()) {
      sharedData.latitude = gps.location.lat();
      sharedData.longitude = gps.location.lng();
    }

    // GPSから有効なUTC時刻が得られているか
    if (gps.time.isValid()) {
      snprintf(sharedData.utc_time, sizeof(sharedData.utc_time), 
               "%02d:%02d:%02d", 
               gps.time.hour(), gps.time.minute(), gps.time.second());
    }

    // 捕捉している衛星数を取得
    if (gps.satellites.isValid()) {
      sharedData.satellites = gps.satellites.value();
    } else {
      sharedData.satellites = 0;
    }

    xSemaphoreGive(dataMutex);
  }

  // 他のシステム処理（シリアル通信やバックグラウンド処理）の動作を助けるための短いウエイト
  delay(1);
}
