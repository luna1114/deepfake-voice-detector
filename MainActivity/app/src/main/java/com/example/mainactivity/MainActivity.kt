package com.example.mainactivity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.mainactivity.ui.detect.DetectionScreen
import com.example.mainactivity.ui.home.HomeScreen
import com.example.mainactivity.ui.theme.AIVoiceDetectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIVoiceDetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Home) }

    when (screen) {
        Screen.Home -> HomeScreen(
            onGetStarted = { screen = Screen.Detection }
        )
        Screen.Detection -> DetectionScreen(
            onBack = { screen = Screen.Home }
        )
    }
}

private enum class Screen { Home, Detection }
