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
            // 権限拒否時のSecurityException等を完全に保護し、安全にnull(アシストなし)を返す
            null
        }
    }

    /**
     * GPSまたはネットワーク等で最後に取得できた有効な位置情報を返す
     */
    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        return try {
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
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
