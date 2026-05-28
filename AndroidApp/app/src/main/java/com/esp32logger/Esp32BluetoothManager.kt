package com.esp32logger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

// Bluetooth Classic（SPP: Serial Port Profile）の標準UUID
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

// 接続先デバイス名
private const val TARGET_DEVICE_NAME = "ESP32_Logger"

// 自動再接続の待機時間（ミリ秒）
private const val RECONNECT_DELAY_MS = 5000L

/**
 * 接続状態を表すシールドクラス
 */
sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
    data class Reconnecting(val attempt: Int) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/**
 * ESP32から受信した1フレームのデータクラス
 * @param rawPulseRpm パルスベースのRPM（Android側でpulsePerRevolutionで割る前の値）
 * @param speed GPS速度 (km/h)
 * @param latitude 緯度
 * @param longitude 経度
 * @param utcTime UTC時刻文字列
 */
data class LoggerFrame(
    val rawPulseRpm: Double,
    val speed: Double,
    val latitude: Double,
    val longitude: Double,
    val utcTime: String
)

/**
 * Bluetooth通信コアクラス
 *
 * 責務:
 * - ESP32_Logger へのBluetooth Classic接続
 * - InputStreamのバックグラウンドループ読み込み（Coroutines + IO Dispatcher）
 * - チェックサム検証（NMEA XOR方式）
 * - 切断検知と自動再接続（5秒間隔リトライ）
 * - StateFlowによるViewModelへのデータ配信
 */
class Esp32BluetoothManager(
    private val bluetoothAdapter: BluetoothAdapter
) {
    // 接続状態のStateFlow（ViewModelがcollectする）
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // 受信フレームのStateFlow
    private val _latestFrame = MutableStateFlow<LoggerFrame?>(null)
    val latestFrame: StateFlow<LoggerFrame?> = _latestFrame.asStateFlow()

    // 通信管理用のCoroutineScope（IO Dispatcherで実行）
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var bluetoothSocket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * 接続を開始する。
     * 既に接続中なら何もしない。
     * Coroutineで非同期実行するため、UIスレッドをブロックしない。
     */
    fun connect() {
        // 既接続または接続中は何もしない
        if (_connectionStatus.value is ConnectionStatus.Connected ||
            _connectionStatus.value is ConnectionStatus.Connecting) return

        reconnectJob?.cancel()
        reconnectJob = managerScope.launch {
            connectInternal(reconnectAttempt = 0)
        }
    }

    /**
     * 切断処理。全Coroutineとソケットを安全にクリーンアップする。
     */
    fun disconnect() {
        reconnectJob?.cancel()
        readJob?.cancel()
        closeSocket()
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    /**
     * Managerのリソースを完全に解放する（Activityのライフサイクル終了時に呼ぶ）
     */
    fun release() {
        managerScope.cancel()
        closeSocket()
    }

    /**
     * 内部接続処理（自動再接続ループを含む）
     * @param reconnectAttempt 再接続試行回数（初回接続は0）
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(reconnectAttempt: Int) {
        _connectionStatus.value = if (reconnectAttempt == 0)
            ConnectionStatus.Connecting
        else
            ConnectionStatus.Reconnecting(reconnectAttempt)

        // ペアリング済みデバイスの中から ESP32_Logger を探す
        val device: BluetoothDevice? = bluetoothAdapter.bondedDevices
            .firstOrNull { it.name == TARGET_DEVICE_NAME }

        if (device == null) {
            _connectionStatus.value = ConnectionStatus.Error(
                "「$TARGET_DEVICE_NAME」がペアリングリストに見つかりません。先にBluetooth設定でペアリングしてください。"
            )
            return
        }

        try {
            // 既存ソケットがあれば閉じる
            closeSocket()

            // SPP UUIDでソケットを作成し接続
            // ※ createRfcommSocketToServiceRecord はブロッキング処理のため IO Dispatcher で実行
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket = socket

            // Bluetooth検出中のスキャンを止めて接続を安定させる
            bluetoothAdapter.cancelDiscovery()

            // ブロッキング接続（IO Dispatcher内なのでUIに影響なし）
            socket.connect()

            _connectionStatus.value = ConnectionStatus.Connected

            // 接続成功後、受信ループを開始
            startReadLoop(socket)

        } catch (e: IOException) {
            // 接続失敗時は5秒後に再接続を試みる
            closeSocket()
            _connectionStatus.value = ConnectionStatus.Reconnecting(reconnectAttempt + 1)

            delay(RECONNECT_DELAY_MS)

            // コルーチンがキャンセルされていなければ再試行
            if (managerScope.isActive) {
                connectInternal(reconnectAttempt + 1)
            }
        }
    }

    /**
     * InputStreamを継続的に読み込むメインループ
     * InputStream.readLine() はブロッキング処理のため IO Dispatcher で実行する
     * 切断検知時は自動で再接続ロジックへ移行する
     */
    private fun startReadLoop(socket: BluetoothSocket) {
        readJob = managerScope.launch {
            try {
                // BufferedReaderで行単位の読み込みを効率化
                val reader = BufferedReader(
                    InputStreamReader(socket.inputStream),
                    1024 // バッファサイズ: 1KB
                )

                while (isActive) {
                    // ブロッキング読み込み（切断時にIOExceptionがスローされる）
                    val line = reader.readLine() ?: break

                    // チェックサム検証とパース処理
                    parseAndValidateFrame(line)?.let { frame ->
                        _latestFrame.value = frame
                    }
                }
            } catch (e: IOException) {
                // 切断検知: 自動再接続ループを開始する
                if (isActive) {
                    _connectionStatus.value = ConnectionStatus.Reconnecting(1)
                    delay(RECONNECT_DELAY_MS)
                    connectInternal(reconnectAttempt = 1)
                }
            }
        }
    }

    /**
     * CSV文字列をパースし、チェックサムを検証する。
     * 形式: [pulse_us],[速度],[緯度],[経度],[時刻]*[HEX チェックサム]
     * ※ ESP32スケッチのCSVフォーマットに準拠
     * @param raw 受信した生の文字列1行
     * @return パース成功かつチェックサムOKならLoggerFrame、それ以外はnull
     */
    private fun parseAndValidateFrame(raw: String): LoggerFrame? {
        return try {
            // "*" でペイロードとチェックサムに分割
            val asteriskIdx = raw.indexOf('*')
            if (asteriskIdx < 0) return null

            val payload = raw.substring(0, asteriskIdx)
            val checksumStr = raw.substring(asteriskIdx + 1).trim()

            // チェックサム検証（ペイロード全体のXOR）
            val expectedChecksum = payload.fold(0) { acc, c -> acc xor c.code }
            val receivedChecksum = checksumStr.toIntOrNull(16) ?: return null

            if (expectedChecksum != receivedChecksum) {
                // チェックサム不一致 → データ破損として破棄
                return null
            }

            // CSV パース: [pulse_us],[速度],[緯度],[経度],[時刻]
            val parts = payload.split(",")
            if (parts.size < 5) return null

            val pulseUs = parts[0].toDoubleOrNull() ?: return null
            val speed   = parts[1].toDoubleOrNull() ?: return null
            val lat     = parts[2].toDoubleOrNull() ?: return null
            val lng     = parts[3].toDoubleOrNull() ?: return null
            val time    = parts[4]

            // pulse_us → RPM 換算
            // 1分 = 60,000,000 マイクロ秒
            // rawPulseRpm = 60,000,000 / pulse_us
            val rawPulseRpm = if (pulseUs > 0) 60_000_000.0 / pulseUs else 0.0

            LoggerFrame(
                rawPulseRpm = rawPulseRpm,
                speed       = speed,
                latitude    = lat,
                longitude   = lng,
                utcTime     = time
            )
        } catch (e: Exception) {
            null // パース失敗は黙って破棄
        }
    }

    /**
     * BluetoothSocketを安全にクローズする
     */
    private fun closeSocket() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // クローズ失敗は無視
        } finally {
            bluetoothSocket = null
        }
    }
}
