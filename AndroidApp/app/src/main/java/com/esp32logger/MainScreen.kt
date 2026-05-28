package com.esp32logger

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.*

// RPM履歴の最大サンプル数（ラインチャート用）
private const val RPM_HISTORY_SIZE = 120

/**
 * メイン画面コンポーザブル
 * Bluetooth権限ダイアログ、デジタルメーター、設定パネルを統合する
 */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // RPM履歴バッファ（ラインチャート用）
    val rpmHistory = remember { ArrayDeque<Float>(RPM_HISTORY_SIZE) }
    LaunchedEffect(uiState.displayRpm) {
        if (rpmHistory.size >= RPM_HISTORY_SIZE) rpmHistory.removeFirst()
        rpmHistory.addLast(uiState.displayRpm.toFloat())
    }

    // =========================================
    // Bluetooth権限リクエスト (Android 12以上)
    // =========================================
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.connect()
        }
    }

    // 設定パネルの表示状態
    var showSettings by remember { mutableStateOf(false) }

    // ダークテーマのベース背景色
    val bgColor = Color(0xFF0D1117)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ───────────── ヘッダー ─────────────
            AppHeader(
                onSettingsClick = { showSettings = !showSettings }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ───────────── 接続状態バッジ ─────────────
            ConnectionStatusBadge(status = uiState.connectionStatus)

            Spacer(modifier = Modifier.height(16.dp))

            // ───────────── RPM アークメーター ─────────────
            RpmArcMeter(
                rpm = uiState.displayRpm,
                maxRpm = 12000.0
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ───────────── 速度・最高速・GPS 情報カード ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DataCard(
                    modifier = Modifier.weight(1f),
                    label = "速度",
                    value = "%.1f".format(uiState.speed),
                    unit = "km/h",
                    icon = Icons.Default.Speed
                )
                DataCard(
                    modifier = Modifier.weight(1f),
                    label = "最高速",
                    value = "%.1f".format(uiState.maxSpeed),
                    unit = "km/h",
                    icon = Icons.Default.TrendingUp
                )
                DataCard(
                    modifier = Modifier.weight(1f),
                    label = "UTC時刻",
                    value = uiState.utcTime,
                    unit = "",
                    icon = Icons.Default.AccessTime
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // GPS座標カード
            GpsCard(lat = uiState.latitude, lng = uiState.longitude)

            Spacer(modifier = Modifier.height(16.dp))

            // ───────────── RPM ラインチャート ─────────────
            RpmLineChart(
                history = rpmHistory.toList(),
                maxRpm = 12000f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ───────────── 設定パネル（折りたたみ） ─────────────
            if (showSettings) {
                SettingsPanel(
                    currentPpr = uiState.pulsePerRevolution,
                    onPprChange = { viewModel.updatePulsePerRevolution(it) },
                    onResetMaxSpeed = { viewModel.resetMaxSpeed() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ───────────── 操作ボタン ─────────────
            ControlButtons(
                status = uiState.connectionStatus,
                isLogging = uiState.isLogging,
                logFilePath = uiState.logFilePath,
                onConnectClick = {
                    // Android 12以上はBluetooth権限を動的に要求
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                        )
                    } else {
                        viewModel.connect()
                    }
                },
                onDisconnectClick = { viewModel.disconnect() },
                onLogStart = { viewModel.startLogging() },
                onLogStop = { viewModel.stopLogging() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────
// ヘッダーコンポーザブル
// ─────────────────────────────────────────────
@Composable
private fun AppHeader(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "ESP32 Logger",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF58A6FF)
        )
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "設定",
                tint = Color(0xFF8B949E)
            )
        }
    }
}

// ─────────────────────────────────────────────
// 接続状態バッジ
// ─────────────────────────────────────────────
@Composable
private fun ConnectionStatusBadge(status: ConnectionStatus) {
    val (text, color) = when (status) {
        is ConnectionStatus.Connected    -> "接続中" to Color(0xFF3FB950)
        is ConnectionStatus.Connecting   -> "接続しています..." to Color(0xFFD29922)
        is ConnectionStatus.Reconnecting -> "再接続中... (${status.attempt}回目)" to Color(0xFFD29922)
        is ConnectionStatus.Disconnected -> "切断" to Color(0xFF8B949E)
        is ConnectionStatus.Error        -> "エラー" to Color(0xFFF85149)
    }

    // 接続中はインジケーターを点滅させる
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val indicatorAlpha = if (status is ConnectionStatus.Connected) 1f else alpha

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = indicatorAlpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

// ─────────────────────────────────────────────
// RPM アークメーター（Canvas描画）
// ─────────────────────────────────────────────
@Composable
private fun RpmArcMeter(rpm: Double, maxRpm: Double) {
    val animatedRpm by animateFloatAsState(
        targetValue = rpm.toFloat(),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "rpm"
    )

    val rpmColor = when {
        rpm > maxRpm * 0.85 -> Color(0xFFF85149) // 赤: 危険域
        rpm > maxRpm * 0.65 -> Color(0xFFD29922) // 黄: 警告域
        else                 -> Color(0xFF58A6FF) // 青: 通常域
    }
    val animatedColor by animateColorAsState(
        targetValue = rpmColor,
        animationSpec = tween(300),
        label = "rpmColor"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRpmArc(
                    rpm = animatedRpm,
                    maxRpm = maxRpm.toFloat(),
                    color = animatedColor
                )
            }

            // RPM数値テキスト
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%,d".format(animatedRpm.toInt()),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "RPM",
                    fontSize = 16.sp,
                    color = Color(0xFF8B949E),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Canvas拡張: RPMアーク（半円ゲージ）を描画する
 */
private fun DrawScope.drawRpmArc(rpm: Float, maxRpm: Float, color: Color) {
    val strokeWidth = 18.dp.toPx()
    val startAngle = 150f
    val sweepTotal = 240f

    // 背景トラック（暗いグレー）
    drawArc(
        color = Color(0xFF21262D),
        startAngle = startAngle,
        sweepAngle = sweepTotal,
        useCenter = false,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
        size = size.copy(
            width  = size.width  - strokeWidth,
            height = size.height - strokeWidth
        )
    )

    // 値アーク（グラデーション風にSweepGradientで描画）
    val fraction = (rpm / maxRpm).coerceIn(0f, 1f)
    if (fraction > 0f) {
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(color.copy(alpha = 0.6f), color),
                center = Offset(size.width / 2f, size.height / 2f)
            ),
            startAngle = startAngle,
            sweepAngle = sweepTotal * fraction,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = size.copy(
                width  = size.width  - strokeWidth,
                height = size.height - strokeWidth
            )
        )
    }
}

// ─────────────────────────────────────────────
// データカード（速度・時刻など）
// ─────────────────────────────────────────────
@Composable
private fun DataCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color(0xFF58A6FF),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = Color(0xFF8B949E)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// GPS座標カード
// ─────────────────────────────────────────────
@Composable
private fun GpsCard(lat: Double, lng: Double) {
    val hasGps = lat != 0.0 || lng != 0.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = "GPS",
                tint = if (hasGps) Color(0xFF3FB950) else Color(0xFF8B949E),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (hasGps) {
                Text(
                    text = "%.6f°, %.6f°".format(lat, lng),
                    fontSize = 13.sp,
                    color = Color(0xFFCDD9E5)
                )
            } else {
                Text(
                    text = "GPS信号を待っています...",
                    fontSize = 13.sp,
                    color = Color(0xFF8B949E)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// RPM リアルタイムラインチャート
// ─────────────────────────────────────────────
@Composable
fun RpmLineChart(
    history: List<Float>,
    maxRpm: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "RPM履歴",
                fontSize = 10.sp,
                color = Color(0xFF8B949E),
                modifier = Modifier.align(Alignment.TopStart)
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, bottom = 4.dp)
            ) {
                if (history.size < 2) return@Canvas

                val w = size.width
                val h = size.height
                val stepX = w / (RPM_HISTORY_SIZE - 1).toFloat()

                // グリッド線（50%ライン）
                drawLine(
                    color = Color(0xFF21262D),
                    start = Offset(0f, h * 0.5f),
                    end = Offset(w, h * 0.5f),
                    strokeWidth = 1.dp.toPx()
                )

                // 折れ線を描画
                val path = Path()
                history.forEachIndexed { i, rpm ->
                    val x = i * stepX + (RPM_HISTORY_SIZE - history.size) * stepX
                    val y = h - (rpm / maxRpm).coerceIn(0f, 1f) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF58A6FF),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // グラデーション塗りつぶし
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(history.size * stepX + (RPM_HISTORY_SIZE - history.size) * stepX, h)
                    lineTo((RPM_HISTORY_SIZE - history.size) * stepX, h)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF58A6FF).copy(alpha = 0.3f), Color.Transparent)
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// 設定パネル（pulsePerRevolution）
// ─────────────────────────────────────────────
@Composable
private fun SettingsPanel(
    currentPpr: Double,
    onPprChange: (Double) -> Unit,
    onResetMaxSpeed: () -> Unit
) {
    // カスタム入力用テキストフィールドの状態
    var customPprText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "RPM補正設定",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF58A6FF)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "pulsePerRevolution = $currentPpr",
                fontSize = 12.sp,
                color = Color(0xFF8B949E)
            )
            Text(
                text = "displayRPM = rawRPM / pulsePerRevolution",
                fontSize = 11.sp,
                color = Color(0xFF8B949E)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // プリセットボタン行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetButton(
                    label = "2スト単気筒",
                    subLabel = "× 1.0",
                    selected = currentPpr == 1.0,
                    onClick = { onPprChange(1.0) },
                    modifier = Modifier.weight(1f)
                )
                PresetButton(
                    label = "4スト単気筒",
                    subLabel = "× 0.5",
                    selected = currentPpr == 0.5,
                    onClick = { onPprChange(0.5) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 任意値入力フィールド
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customPprText,
                    onValueChange = { customPprText = it },
                    label = { Text("任意のpulse/rev", color = Color(0xFF8B949E), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor  = Color(0xFF58A6FF),
                        unfocusedBorderColor = Color(0xFF30363D),
                        focusedTextColor    = Color.White,
                        unfocusedTextColor  = Color.White
                    ),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val v = customPprText.toDoubleOrNull()
                        if (v != null && v > 0) onPprChange(v)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                ) {
                    Text("適用", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 最高速リセットボタン
            Button(
                onClick = onResetMaxSpeed,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB62324)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("最高速度の記録をリセット", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    subLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) Color(0xFF58A6FF) else Color(0xFF30363D)
    val bgColor     = if (selected) Color(0xFF58A6FF).copy(alpha = 0.15f) else Color.Transparent

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.border(1.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bgColor)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subLabel, color = Color(0xFF8B949E), fontSize = 11.sp)
        }
    }
}

// ─────────────────────────────────────────────
// 操作ボタン（接続/切断、ログ開始/停止）
// ─────────────────────────────────────────────
@Composable
private fun ControlButtons(
    status: ConnectionStatus,
    isLogging: Boolean,
    logFilePath: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onLogStart: () -> Unit,
    onLogStop: () -> Unit
) {
    val isConnected = status is ConnectionStatus.Connected
    val isConnecting = status is ConnectionStatus.Connecting ||
            status is ConnectionStatus.Reconnecting

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 接続/切断ボタン
        Button(
            onClick = if (isConnected || isConnecting) onDisconnectClick else onConnectClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected || isConnecting)
                    Color(0xFFB62324) else Color(0xFF238636)
            )
        ) {
            Icon(
                imageVector = if (isConnected || isConnecting)
                    Icons.Default.BluetoothDisabled else Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isConnecting -> "接続中... (タップで中止)"
                    isConnected  -> "切断する"
                    else         -> "ESP32_Loggerに接続"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        // ログ開始/停止ボタン（接続中のみ有効）
        if (isConnected) {
            Button(
                onClick = if (isLogging) onLogStop else onLogStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLogging) Color(0xFFD29922) else Color(0xFF1F6FEB)
                )
            ) {
                Icon(
                    imageVector = if (isLogging)
                        Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isLogging) Color.Black else Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isLogging) "ログ記録を停止" else "ログ記録を開始",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (isLogging) Color.Black else Color.White
                )
            }

            // ログファイルパス表示
            if (isLogging && logFilePath.isNotEmpty()) {
                Text(
                    text = "📁 $logFilePath",
                    fontSize = 10.sp,
                    color = Color(0xFF8B949E),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
