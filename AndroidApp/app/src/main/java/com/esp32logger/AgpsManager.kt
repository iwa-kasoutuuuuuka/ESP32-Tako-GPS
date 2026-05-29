package com.esp32logger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * スマートフォンのGPS/ネットワーク位置情報を取得し、
 * ESP32（およびNEO-6M）用のアシストデータに整形するマネージャークラス
 */
class AgpsManager(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    companion object {
        // 使用する位置情報の最大許容経過時間（30分以内のデータのみ有効）
        // 古い位置情報でGPSアシストすると測位が逆に遅くなるため
        private const val MAX_LOCATION_AGE_MS = 30 * 60 * 1000L // 30分
    }

    /**
     * 最新のスマートフォンGPS情報を基にA-GPS用アペンド文を組み立てる
     * フォーマット: $AGPS,緯度,経度,高度,UTC日付,UTC時刻*HEXチェックサム\n
     *
     * @return 有効な位置情報が取得できた場合はNMEAアシストパケット文字列、
     *         権限未付与・位置情報古すぎ・取得失敗の場合はnull
     */
    fun getAgpsPayload(): String? {
        // Vuln #5修正: @SuppressLint に頼らず、事前に権限を明示的にチェック
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return null // 権限なし → 静かにスキップ
        }

        return try {
            val location = getLastKnownLocation() ?: return null

            val lat = location.latitude
            val lon = location.longitude
            val alt = location.altitude

            val utcFormatTime = SimpleDateFormat("HHmmss.00", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val utcFormatDate = SimpleDateFormat("ddMMyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val gpsTime = Date(location.time)
            val timeStr = utcFormatTime.format(gpsTime)
            val dateStr = utcFormatDate.format(gpsTime)

            val payload = String.format(Locale.US, "AGPS,%.6f,%.6f,%.1f,%s,%s", lat, lon, alt, dateStr, timeStr)

            val checksum = payload.fold(0) { acc, c -> acc xor c.code }
            val checksumStr = String.format("%02X", checksum)

            "$$payload*$checksumStr\n"
        } catch (e: Exception) {
            // 予期しない例外を保護
            null
        }
    }

    /**
     * GPSまたはネットワーク等で最後に取得できた有効な位置情報を返す
     *
     * Vuln #4修正: 30分以内の「新鮮な」データのみを返す。
     * 古いキャッシュ位置でアシスト注入すると、NEO-6Mが誤った初期位置から
     * 衛星検索を始めて測位が逆に遅くなる問題を防止する。
     */
    @SuppressLint("MissingPermission") // 呼び出し元(getAgpsPayload)で権限チェック済み
    private fun getLastKnownLocation(): Location? {
        return try {
            val now = System.currentTimeMillis()
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null

            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue

                // Vuln #4修正: 30分以内のデータのみ有効とする（古いキャッシュを排除）
                val ageMs = now - l.time
                if (ageMs > MAX_LOCATION_AGE_MS) continue

                if (bestLocation == null || l.time > bestLocation.time) {
                    bestLocation = l
                }
            }
            bestLocation
        } catch (e: Exception) {
            null
        }
    }
}
