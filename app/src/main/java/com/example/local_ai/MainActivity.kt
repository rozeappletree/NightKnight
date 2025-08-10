package com.example.local_ai

import android.app.Activity // Added
import android.app.AppOpsManager // Added
import android.content.Context // Added
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process // Added
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
// ... other imports
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext // Added
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.local_ai.ui.theme.LocalaiTheme
import com.example.local_ai.ui.usage.AppUsageViewModel
import com.example.local_ai.ui.usage.FormattedAppUsageEvent

class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    // private val USAGE_STATS_PERMISSION_REQUEST_CODE = 5678 // Not strictly needed for result if just navigating
    private var isServiceActive by mutableStateOf(false)

    // Helper function to check if usage stats permission is granted
    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Helper function to request usage stats permission
    private fun requestUsageStatsPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: Try with URI if direct action fails (less common)
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(intent)
            } catch (e2: Exception) {
                // Handle case where settings screen cannot be opened
                android.widget.Toast.makeText(activity, "Could not open Usage Access settings.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LocalaiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val pagerState = rememberPagerState(pageCount = { 3 })
                    val context = LocalContext.current // Get context for permission check

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIndex ->
                        when (pageIndex) {
                            0 -> MainScreenPage(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                                isServiceActive = isServiceActive,
                                onToggleService = {
                                    if (isServiceActive) {
                                        stopFloatingIconService()
                                    } else {
                                        // Unified permission check and service start
                                        checkPermissionsAndStartService()
                                    }
                                }
                            )
                            1 -> DailyActivitiesPage(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                            2 -> DailyUsageDigestPage(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        if (!hasUsageStatsPermission(this)) {
            requestUsageStatsPermission(this)
            // User needs to grant permission and try again.
            // A Toast message here could be helpful.
            android.widget.Toast.makeText(this, "Usage Access permission required. Please grant it and try again.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Usage stats granted, now check for overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } else {
            // Both permissions are granted (or overlay not needed)
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
            // Returned from overlay permission screen
            // We need to re-check everything because the user might have revoked usage stats
            // in the meantime, though unlikely. The checkPermissionsAndStartService handles all.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                 // Overlay granted, now re-run the full check which includes usage stats
                checkPermissionsAndStartService()
            } else {
                android.widget.Toast.makeText(this, "Overlay permission not granted.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun MainScreenPage(modifier: Modifier = Modifier, isServiceActive: Boolean, onToggleService: () -> Unit) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🛡️ Digital Wellbeing Assistant ⚔️\n",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        IconButton(
            onClick = onToggleService,
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

// ... DailyActivitiesPage, DailyActivityRow, DailyUsageDigestPage, Greeting, GreetingPreview ...
// (These remain unchanged from your latest version)
@Composable
fun DailyActivitiesPage(
    modifier: Modifier = Modifier,
    viewModel: AppUsageViewModel = viewModel() // Obtain ViewModel
) {
    // Collect the state from ViewModel
    val activities by viewModel.dailyActivities.collectAsState()

    // Load activities when the composable is first launched
    LaunchedEffect(Unit) {
        viewModel.loadDailyActivitiesForToday()
    }

    Column(modifier = modifier.padding(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Daily Activities",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.loadDailyActivitiesForToday() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh Activities"
                )
            }
        }

        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Time", modifier = Modifier.weight(0.25f), style = MaterialTheme.typography.titleSmall)
            Text("App name", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.titleSmall)
            Text("Usage time", modifier = Modifier.weight(0.25f), textAlign = TextAlign.End, style = MaterialTheme.typography.titleSmall)
        }
        Divider()

        if (activities.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity recorded for today yet, or enable Usage Access permission.")
            }
        } else {
            LazyColumn {
                items(activities) { activity ->
                    DailyActivityRow(activity)
                    Divider()
                }
            }
        }
    }
}

@Composable
fun DailyActivityRow(activity: FormattedAppUsageEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(activity.time, modifier = Modifier.weight(0.25f))
        Text(activity.appName, modifier = Modifier.weight(0.5f))
        Text(activity.usageTime, modifier = Modifier.weight(0.25f), textAlign = TextAlign.End)
    }
}


@Composable
fun DailyUsageDigestPage(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { // modifier includes .fillMaxSize() and padding
        Text("Daily Usage Digest Page (Coming Soon)")
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
        // Previewing the main screen page content
        var isPreviewingServiceActive by remember { mutableStateOf(false) }
        MainScreenPage(
            isServiceActive = isPreviewingServiceActive,
            onToggleService = { isPreviewingServiceActive = !isPreviewingServiceActive }
        )
    }
}
