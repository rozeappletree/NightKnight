package com.example.local_ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign // Import TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp // Import dp
import androidx.core.content.ContextCompat
import com.example.local_ai.ui.theme.LocalaiTheme

class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    private var isServiceActive by mutableStateOf(false) // Track service state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LocalaiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🛡️ Digital Wellbeing Assistant ⚔️\n",
                            textAlign = TextAlign.Center, // Center align the text
                            modifier = Modifier.padding(horizontal = 16.dp) // Add horizontal padding
                        )
                        IconButton(
                            onClick = {
                                if (isServiceActive) {
                                    stopFloatingIconService()
                                } else {
                                    requestOverlayPermissionAndStartService()
                                }
                            },
                            modifier = Modifier.shadow(elevation = 8.dp, shape = androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PowerSettingsNew,
                                contentDescription = if (isServiceActive) "Stop Service" else "Start Service",
                                tint = if (isServiceActive) Color(0xFF5070FE) else Color.Gray,
                                modifier = Modifier.size(72.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestOverlayPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } else {
            startFloatingIconService()
        }
    }

    private fun startFloatingIconService() {
        val intent = Intent(this, FloatingIconService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        isServiceActive = true
    }

     private fun stopFloatingIconService() {
        val intent = Intent(this, FloatingIconService::class.java)
        stopService(intent)
        isServiceActive = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingIconService()
            }
        }
    }
}

// Keeping Greeting and Preview for now, can be removed if not needed.
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LocalaiTheme {
        // Preview with the new IconButton
        var isPreviewingServiceActive by remember { mutableStateOf(false) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🛡️ Digital Wellbeing Assistant ⚔️\n",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(
                onClick = { isPreviewingServiceActive = !isPreviewingServiceActive },
                modifier = Modifier.shadow(elevation = 8.dp, shape = androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Power Button",
                    tint = if (isPreviewingServiceActive) Color(0xFF5070FE) else Color.Gray,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}
