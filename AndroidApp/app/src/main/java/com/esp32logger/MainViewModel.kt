package com.esp32logger

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// DataStore インスタンスをApplicationレベルのシングルトンとして定義
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// DataStore のキー定義
private object PreferenceKeys {
    val PULSE_PER_REVOLUTION = doublePreferencesKey("pulse_per_revolution")
}

// デフォルト値: 4スト単気筒（0.5 pulse/revolution）
private const val DEFAULT_PULSE_PER_REVOLUTION = 0.5

/**
 * UI表示用の集約データクラス
 */
data class UiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val rawPulseRpm: Double = 0.0,
    val displayRpm: Double = 0.0,         // pulsePerRevolutionで補正済みの表示RPM
    val speed: Double = 0.0,
    val maxSpeed: Double = 0.0,           // 最高速度
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val utcTime: String = "--:--:--",
    val isLogging: Boolean = false,
    val pulsePerRevolution: Double = DEFAULT_PULSE_PER_REVOLUTION,
    val logFilePath: String = ""
)

/**
 * MainViewModel
 *
 * 責務:
 * - Esp32BluetoothManagerのライフサイクル管理
 * - StateFlowによるUI状態の一元管理
 * - DataStoreへのpulsePerRevolution設定の読み書き
 * - スマホ内部ストレージへのCSVロギング（IO Dispatcher）
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Bluetooth Adapter を取得
    private val bluetoothAdapter = (application
        .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter

    // Bluetooth通信コアクラス
    private val btManager = Esp32BluetoothManager(application, bluetoothAdapter)

    // UI状態のStateFlow
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ログファイルのライター（null = ロギング停止中）
    private var logWriter: java.io.BufferedWriter? = null

    // JST時刻フォーマット
    private val jstDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.JAPAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }

    init {
        // DataStoreからpulsePerRevolution設定を読み込む
        viewModelScope.launch {
            application.dataStore.data
                .map { prefs ->
                    prefs[PreferenceKeys.PULSE_PER_REVOLUTION] ?: DEFAULT_PULSE_PER_REVOLUTION
                }
                .collect { ppr ->
                    _uiState.update { it.copy(pulsePerRevolution = ppr) }
                }
        }

        // Bluetooth接続状態の変化をUIに反映
        viewModelScope.launch {
            btManager.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }

                // 切断時はメーター値をリセット
                if (status is ConnectionStatus.Disconnected ||
                    status is ConnectionStatus.Error) {
                    _uiState.update { it.copy(
                        rawPulseRpm = 0.0,
                        displayRpm = 0.0,
                        speed = 0.0
                    )}
                }
            }
        }

        // 受信フレームをUIとロギングに反映
        viewModelScope.launch {
            btManager.latestFrame.filterNotNull().collect { frame ->
                val ppr = _uiState.value.pulsePerRevolution
                // Android側でpulsePerRevolutionを用いてRPM換算
                // displayRpm = rawPulseRpm / pulsePerRevolution
                val displayRpm = if (ppr > 0) frame.rawPulseRpm / ppr else 0.0

                _uiState.update { current ->
                    val newMax = kotlin.math.max(current.maxSpeed, frame.speed)
                    current.copy(
                        rawPulseRpm = frame.rawPulseRpm,
                        displayRpm  = displayRpm,
                        speed       = frame.speed,
                        maxSpeed    = newMax,
                        latitude    = frame.latitude,
                        longitude   = frame.longitude,
                        utcTime     = frame.utcTime
                    )
                }

                // ロギングONならファイルに追記
                if (_uiState.value.isLogging) {
                    appendToLog(frame, displayRpm)
                }
            }
        }
    }

    /**
     * Bluetooth接続を開始する
     */
    fun connect() {
        btManager.connect()
    }

    /**
     * Bluetooth接続を切断する
     */
    fun disconnect() {
        btManager.disconnect()
        stopLogging()
    }

    /**
     * ロギングを開始する。
     * ファイルはアプリ専用の外部ストレージ（Scoped Storage対応）に保存。
     * Context.getExternalFilesDirを使用することで、READ_EXTERNAL_STORAGE権限不要。
     */
    fun startLogging() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                // 外部公開可能なアプリ専用ディレクトリ（Scoped Storage対応）
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val fileName = "log_${fileDateFormat.format(Date())}.csv"
                val file = File(dir, fileName)

                // ファイルが新規の場合はCSVヘッダーを書く
                val isNewFile = !file.exists()
                val writer = java.io.FileOutputStream(file, true).bufferedWriter()

                if (isNewFile) {
                    writer.write("JST時刻,raw_pulse_rpm,display_rpm,速度_kmh,緯度,経度,UTC時刻")
                    writer.newLine()
                }

                logWriter = writer

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        isLogging = true,
                        logFilePath = file.absolutePath
                    )}
                }
            } catch (e: Exception) {
                // ファイル作成失敗
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLogging = false) }
                }
            }
        }
    }

    /**
     * ロギングを停止する
     */
    fun stopLogging() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logWriter?.flush()
                logWriter?.close()
            } catch (e: Exception) {
                // クローズエラーは無視
            } finally {
                logWriter = null
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLogging = false) }
                }
            }
        }
    }

    /**
     * ログファイルにデータを1行追記する（IO Dispatcherで実行済みであること）
     */
    private suspend fun appendToLog(frame: LoggerFrame, displayRpm: Double) {
        withContext(Dispatchers.IO) {
            try {
                val jstTime = jstDateFormat.format(Date())
                val line = "$jstTime,${frame.rawPulseRpm.toInt()}," +
                        "${displayRpm.toInt()},${frame.speed}," +
                        "${frame.latitude},${frame.longitude},${frame.utcTime}"
                logWriter?.write(line)
                logWriter?.newLine()
                logWriter?.flush()
            } catch (e: Exception) {
                // 書き込みエラー時はロギングを停止
                logWriter = null
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLogging = false) }
                }
            }
        }
    }

    /**
     * pulsePerRevolution設定を変更しDataStoreに永続保存する。
     * UI変更時に即座にdisplayRpmを再計算する。
     * @param value 新しいpulsePerRevolution値（例: 0.5=4st, 1.0=2st）
     */
    fun updatePulsePerRevolution(value: Double) {
        viewModelScope.launch {
            // DataStoreに永続保存
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[PreferenceKeys.PULSE_PER_REVOLUTION] = value
            }

            // 即座にdisplayRpmを再計算（既存のrawPulseRpmを使用）
            val currentRaw = _uiState.value.rawPulseRpm
            val newDisplayRpm = if (value > 0) currentRaw / value else 0.0
            _uiState.update { it.copy(
                pulsePerRevolution = value,
                displayRpm = newDisplayRpm
            )}
        }
    }

    /**
     * 最高速度の記録をリセットする
     */
    fun resetMaxSpeed() {
        _uiState.update { it.copy(maxSpeed = 0.0) }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModelが破棄される際にBluetoothリソースを解放
        btManager.release()
        viewModelScope.launch(Dispatchers.IO) {
            logWriter?.flush()
            logWriter?.close()
        }
    }
}
