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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
// import androidx.compose.foundation.layout.width // No longer exclusively using width for these sections
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow // Added
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.local_ai.ui.theme.LocalaiTheme
import com.example.local_ai.ui.usage.AppUsageViewModel
import com.example.local_ai.ui.usage.FormattedAppUsageEvent
import com.example.local_ai.ui.usage.DailyUsageDigestViewModel // Added
import com.example.local_ai.ui.usage.DigestGeneralStats // Added
import com.example.local_ai.ui.usage.AppUsageStat // Added
import com.example.local_ai.ui.usage.CategoryUsageStat // Added
import com.example.local_ai.ui.usage.HourlyUsageStat // Added

class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    private var isServiceActive by mutableStateOf(false)

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(intent)
            } catch (e2: Exception) {
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
                    // val context = LocalContext.current // No longer needed here

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
            android.widget.Toast.makeText(this, "Usage Access permission required. Please grant it and try again.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

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

@Composable
fun DailyActivitiesPage(
    modifier: Modifier = Modifier,
    viewModel: AppUsageViewModel = viewModel()
) {
    val activities by viewModel.dailyActivities.collectAsState()
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
fun DailyUsageDigestPage(
    modifier: Modifier = Modifier,
    viewModel: DailyUsageDigestViewModel = viewModel()
) {
    val currentDate by viewModel.currentDate.collectAsState()
    val digestStats by viewModel.digestStats.collectAsState()
    val topApps by viewModel.topApps.collectAsState()
    val pinnedApps by viewModel.pinnedApps.collectAsState()
    val categoryUsage by viewModel.categoryUsage.collectAsState()
    val hourlyUsage by viewModel.hourlyUsage.collectAsState()
    val ignoredAppCount by viewModel.ignoredAppCount.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDigestData() // Initial load
    }

    LazyColumn(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxSize()
    ) {
        item {
            Text(
                text = currentDate,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Digest Section
        item {
            DigestSection(digestStats)
            Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
        }

        // Top Apps Section
        item {
            AppUsageListSection(title = "Top apps", apps = topApps)
            Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
        }

        // Pinned Apps Section (Optional)
        if (pinnedApps.isNotEmpty()) {
            item {
                AppUsageListSection(title = "Pinned apps", apps = pinnedApps)
                Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
            }
        }

        // Category Usage Section
        item {
            CategoryUsageSection(categories = categoryUsage)
            Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
        }

        // Hourly Usage Section
        item {
            HourlyUsageSection(hourlyData = hourlyUsage)
            Spacer(modifier = Modifier.height(8.dp)) // Reduced spacing
        }

        // Ignored Apps Count
        item {
            Text(
                text = "Usage ignored apps: $ignoredAppCount",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
fun DigestSection(stats: List<DigestGeneralStats>) {
    if (stats.isEmpty()) return
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Digest", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.4f))
                Text("Usage time", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                Text("Change", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f)) 
                Text("Check device", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                Text("Change", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
            }
            stats.forEachIndexed { index, stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stat.period, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
                    Text(stat.usageTime, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                    Text(stat.usageTimeChange, color = getChangeColor(stat.usageTimeChange), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
                    Text(stat.deviceChecks, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                    Text(stat.deviceChecksChange, color = getChangeColor(stat.deviceChecksChange), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
                }
                if (index < stats.size - 1) {
                    Divider()
                }
            }
        }
    }
}

@Composable
fun AppUsageListSection(title: String, apps: List<AppUsageStat>) {
    if (apps.isEmpty() && title == "Pinned apps") return // Don't show empty Pinned Apps section
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Usage time", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                Text("Change", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f)) 
                Text("Access count", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                Text("Change", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
            }
            if (apps.isEmpty()){
                 Text("No data available for $title", modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally))
            } else {
                apps.forEachIndexed { index, app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(app.appName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.usageTime, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                        Text(app.usageTimeChange, color = getChangeColor(app.usageTimeChange), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
                        Text(app.accessCount, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.2f))
                        Text(app.accessCountChange, color = getChangeColor(app.accessCountChange), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
                    }
                    if (index < apps.size - 1) {
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryUsageSection(categories: List<CategoryUsageStat>) {
    if (categories.isEmpty()) return
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Category", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.6f))
                Text("Usage time (%)", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.3f))
                Text("Change", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
            }
            categories.forEachIndexed { index, category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category.categoryName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(category.usageTimePercentage, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.3f))
                    Text(category.usageTimeChange, color = getChangeColor(category.usageTimeChange), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(0.1f))
                }
                if (index < categories.size - 1) {
                    Divider()
                }
            }
        }
    }
}

@Composable
fun HourlyUsageSection(hourlyData: List<HourlyUsageStat>) {
    if (hourlyData.isEmpty()) return
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.2f))
                Text("Usage time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.5f)) // This text was part of the original request's appearance
                Text("", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.3f), textAlign = TextAlign.End) //This refers to the usage time string like "56m 42s"
            }
            hourlyData.forEachIndexed { index, hourStat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min) // Ensures items in row have same height for alignment
                        .padding(vertical = 4.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(hourStat.hour, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.2f), textAlign = TextAlign.End)
                    LinearProgressIndicator(
                        progress = { hourStat.usageProportion },
                        modifier = Modifier.weight(0.5f).padding(horizontal = 8.dp).height(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(hourStat.usageTime, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f), textAlign = TextAlign.End)
                }
                 if (index < hourlyData.size - 1) {
                    // No divider for a cleaner bar graph look
                }
            }
        }
    }
}

@Composable
fun getChangeColor(change: String): Color {
    return when {
        change.startsWith("+") -> Color(0xFF4CAF50) // Green for positive
        change.startsWith("-") -> Color(0xFFF44336) // Red for negative
        change == "＋" -> Color(0xFF4CAF50) // Special case for new item ("New" in some contexts)
        else -> MaterialTheme.colorScheme.onSurface // Default
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
        var isPreviewingServiceActive by remember { mutableStateOf(false) }
        MainScreenPage(
            isServiceActive = isPreviewingServiceActive,
            onToggleService = { isPreviewingServiceActive = !isPreviewingServiceActive }
        )
    }
}

