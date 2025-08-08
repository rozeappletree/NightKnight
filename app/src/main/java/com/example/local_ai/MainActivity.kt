package com.example.local_ai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
// import androidx.compose.foundation.layout.Arrangement // No longer directly used here
// import androidx.compose.foundation.layout.Column // No longer directly used here
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
// import androidx.compose.foundation.layout.size // No longer directly used here
// import androidx.compose.material.icons.Icons // No longer directly used here
// import androidx.compose.material.icons.filled.PowerSettingsNew // No longer directly used here
// import androidx.compose.material3.Icon // No longer directly used here
// import androidx.compose.material3.IconButton // No longer directly used here
import androidx.compose.material3.Scaffold
// import androidx.compose.material3.Text // No longer directly used here
// import androidx.compose.runtime.Composable // Greeting Composable remains
import androidx.compose.runtime.getValue // Retained for isServiceActive if used elsewhere
import androidx.compose.runtime.mutableStateOf // Retained for isServiceActive
import androidx.compose.runtime.remember // Retained for GreetingPreview
import androidx.compose.runtime.setValue // Retained for isServiceActive
// import androidx.compose.ui.Alignment // No longer directly used here
import androidx.compose.ui.Modifier
// import androidx.compose.ui.draw.shadow // No longer directly used here
// import androidx.compose.ui.graphics.Color // No longer directly used here
// import androidx.compose.ui.text.style.TextAlign // No longer directly used here
import androidx.compose.ui.tooling.preview.Preview // Retained for GreetingPreview
// import androidx.compose.ui.unit.dp // No longer directly used here
import androidx.compose.material3.Text // Keep for Greeting
import androidx.compose.runtime.Composable // Keep for Greeting
import androidx.core.content.ContextCompat
import com.example.local_ai.ui.AppUsageStatsScreen // Import the new screen
import com.example.local_ai.ui.theme.LocalaiTheme


// imports for preview that might be using some of the commented out UI elements
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size


class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    private var isServiceActive by mutableStateOf(false) // Track service state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LocalaiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppUsageStatsScreen(modifier = Modifier.padding(innerPadding)) // Display the new screen
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
        // Preview with the new IconButton (original preview, might need adjustment or removal)
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
