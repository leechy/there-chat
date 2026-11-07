package com.therechat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.therechat.app.feature.map.MapHomeScreen
import com.therechat.app.ui.theme.ThereTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThereTheme {
                MapHomeScreen()
            }
        }
    }
}
