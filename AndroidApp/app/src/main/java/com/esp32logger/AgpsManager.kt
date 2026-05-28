package com.esp32logger

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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

    /**
     * 最新のスマートフォンGPS情報を基にA-GPS用アペンド文を組み立てる
     * フォーマット: $AGPS,緯度,経度,高度,UTC日付,UTC時刻*HEXチェックサム\n
     */
    @SuppressLint("MissingPermission")
    fun getAgpsPayload(): String? {
        val location = getLastKnownLocation() ?: return null
        
        val lat = location.latitude
        val lon = location.longitude
        val alt = location.altitude

        // NMEA $PUBX が要求するUTC形式（時刻: HHMMSS.00, 日付: DDMMYY）
        val utcFormatTime = SimpleDateFormat("HHmmss.00", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utcFormatDate = SimpleDateFormat("ddMMyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        
        // 端末時刻ではなくGPSで最後に取得した時間を使用
        val gpsTime = Date(location.time)
        val timeStr = utcFormatTime.format(gpsTime)
        val dateStr = utcFormatDate.format(gpsTime)

        // アシストCSVデータ部の構築 (AGPSヘッダー、緯度、経度、高度、日付、時刻)
        val payload = String.format(Locale.US, "AGPS,%.6f,%.6f,%.1f,%s,%s", lat, lon, alt, dateStr, timeStr)
        
        // XOR演算によるチェックサム算出 (NMEA-0183準拠)
        val checksum = payload.fold(0) { acc, c -> acc xor c.code }
        val checksumStr = String.format("%02X", checksum)
        
        return "$$payload*$checksumStr\n"
    }

    /**
     * GPSまたはネットワーク等で最後に取得できた有効な位置情報を返す
     */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.time > bestLocation.time) {
                bestLocation = l
            }
        }
        return bestLocation
    }
}
