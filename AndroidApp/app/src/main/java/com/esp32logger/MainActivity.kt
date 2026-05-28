package com.esp32logger

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // ダークテーマをベースとしたカスタムテーマ
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary    = Color(0xFF58A6FF),
                    background = Color(0xFF0D1117),
                    surface    = Color(0xFF161B22)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0D1117)
                ) {
                    MainScreen()
                }
            }
        }
    }
}
