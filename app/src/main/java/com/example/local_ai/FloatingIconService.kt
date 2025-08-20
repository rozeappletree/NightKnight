package com.example.local_ai

import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner // Added by user, used in code
import ai.liquid.leap.downloader.LeapDownloadableModel // New path
import ai.liquid.leap.downloader.LeapModelDownloader // New path
import ai.liquid.leap.gson.registerLeapAdapters
import ai.liquid.leap.message.MessageResponse // New path
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStats // Added by user
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar // Self-added, used in code
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.local_ai.data.db.AppDatabase
import com.example.local_ai.data.db.AppUsageDao
import com.example.local_ai.data.db.AppUsageEvent
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.Random // Self-added, used in code
import java.util.concurrent.TimeUnit

class FloatingIconService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingIconText: TextView
    private lateinit var appUsageLabelText: TextView
    private lateinit var appUsageProgressBar: ProgressBar
    private val appUsagePercentages = mutableMapOf<String, Int>()
    private val randomGenerator = Random()

    private lateinit var params: WindowManager.LayoutParams
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private val clickThreshold = 10

    private lateinit var binView: ImageView
    private lateinit var binParams: WindowManager.LayoutParams
    private var screenHeight: Int = 0
    private var screenWidth: Int = 0

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var modelRunner: ModelRunner? = null
    private var conversation: Conversation? = null
    private var messageGenerationJob: Job? = null
    private val gson = GsonBuilder().registerLeapAdapters().create()

    private lateinit var appUsageDao: AppUsageDao
    private var dataCollectionJob: Job? = null
    private val DATA_COLLECTION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
    private val DATA_QUERY_WINDOW_MS = TimeUnit.HOURS.toMillis(1)

    companion object {
        const val MODEL_SLUG = "lfm2-1.2b"
        const val QUANTIZATION_SLUG = "lfm2-1.2b-20250710-8da4w"
        const val SYSTEM_PROMPT = "You are an roman era Knight AI advisor who asks users to reduce screen time during night time so that they can sleep peacefully. You witty, wicked smart and extremely persuasive."
        const val MESSAGE_REFRESH_INTERVAL_MS = 5000L
        const val NOTIFICATION_CHANNEL_ID = "FloatingIconServiceChannel"
        const val NOTIFICATION_ID = 1
        const val TAG = "FloatingIconService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        setupWindowManagerAndFloatingView()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing service..."))

        Log.d(TAG, "Service created. Starting model loading and message generation.")
        serviceScope.launch {
            loadModelAndStartMessageGeneration(
                onStatusChange = { status ->
                    Log.i(TAG, "Model loading status: $status")
                    updateNotification("Model: $status")
                },
                onError = { error ->
                    Log.e(TAG, "Model loading failed", error)
                    updateNotification("Error: Model load failed")
                    updateText("Error loading model.")
                }
            )
        }

        appUsageDao = AppDatabase.getDatabase(this).appUsageDao()

        if (hasUsageStatsPermission(this)) {
            Log.d(TAG, "Usage stats permission granted. Starting data collection.")
            startDataCollection()
        } else {
            Log.w(TAG, "Usage stats permission NOT granted. Data collection will not start.")
        }
    }

    private fun setupWindowManagerAndFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_icon_layout, null)
        floatingIconText = floatingView.findViewById(R.id.floating_icon_text)
        appUsageLabelText = floatingView.findViewById(R.id.app_usage_label)
        appUsageProgressBar = floatingView.findViewById(R.id.app_usage_bar)

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        binView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
        }
        binParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 50
            y = 50
        }

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenHeight = displayMetrics.heightPixels
        screenWidth = displayMetrics.widthPixels

        updateText("Loading...")
        updateAppUsageLabelAndProgress("App usage", 0)

        floatingView.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                showBinIcon()
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(floatingView, params)
                if (isViewOverlapping(floatingView, binView)) {
                    binView.setColorFilter(getColor(R.color.red))
                } else {
                    binView.clearColorFilter()
                }
            }
            MotionEvent.ACTION_UP -> {
                hideBinIcon()
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                if (kotlin.math.abs(deltaX) < clickThreshold && kotlin.math.abs(deltaY) < clickThreshold) {
                    Toast.makeText(this@FloatingIconService, "Digital Wellbeing Service Running", Toast.LENGTH_SHORT).show()
                } else {
                    if (isViewOverlapping(floatingView, binView)) {
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun loadModelAndStartMessageGeneration(onStatusChange: (String) -> Unit, onError: (Throwable) -> Unit) {
        try {
            val resolvingMsg = "Resolving model..."
            onStatusChange(resolvingMsg)
            updateText(resolvingMsg)
            val modelToUse = ai.liquid.leap.downloader.LeapDownloadableModel.resolve(MODEL_SLUG, QUANTIZATION_SLUG)
            if (modelToUse == null) {
                throw RuntimeException("Model $QUANTIZATION_SLUG not found in Leap Model Library!")
            }

            val modelDownloader = ai.liquid.leap.downloader.LeapModelDownloader(applicationContext)
            val checkingStatusMsg = "Checking download status..."
            onStatusChange(checkingStatusMsg)
            updateText(checkingStatusMsg)

            if (modelDownloader.queryStatus(modelToUse).type != ai.liquid.leap.downloader.LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED) {
                val requestingDownloadMsg = "Requesting model download..."
                onStatusChange(requestingDownloadMsg)
                Log.d(TAG, "MODEL DOWNLOAD CODE IS BEING TRIGGERED")
                modelDownloader.requestDownloadModel(modelToUse)
                var isModelAvailable = false
                while (!isModelAvailable) {
                    val status = modelDownloader.queryStatus(modelToUse)
                    var currentStatusMsg = ""
                    when (status.type) {
                        ai.liquid.leap.downloader.LeapModelDownloader.ModelDownloadStatusType.NOT_ON_LOCAL -> {
                            currentStatusMsg = "Model not downloaded. Waiting..."
                        }
                        ai.liquid.leap.downloader.LeapModelDownloader.ModelDownloadStatusType.DOWNLOAD_IN_PROGRESS -> {
                            currentStatusMsg = "Downloading: ${String.format("%.2f", status.progress * 100.0)}%"
                        }
                        ai.liquid.leap.downloader.LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED -> {
                            currentStatusMsg = "Model downloaded."
                            isModelAvailable = true
                        }
                    }
                    onStatusChange(currentStatusMsg)
                    updateText(currentStatusMsg)
                    if (!isModelAvailable) {
                         delay(1000)
                    }
                }
            } else {
                val alreadyDownloadedMsg = "Model already downloaded."
                onStatusChange(alreadyDownloadedMsg)
                updateText(alreadyDownloadedMsg)
            }

            val modelFile = modelDownloader.getModelFile(modelToUse)
            val loadingFromFileMsg = "Loading model..."
            onStatusChange("Loading model from: ${modelFile.path}")
            updateText(loadingFromFileMsg)
            this.modelRunner = LeapClient.loadModel(modelFile.path)

            val modelLoadedMsg = "Model loaded. Starting message generation."
            onStatusChange(modelLoadedMsg)
            updateText("Generating reminder...")
            startPeriodicMessageGeneration()
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadModelAndStartMessageGeneration", e)
            onError(e)
        }
    }

    private fun startPeriodicMessageGeneration() {
        messageGenerationJob?.cancel()
        messageGenerationJob = serviceScope.launch {
            while (isActive) {
                generateWellbeingMessage()
                delay(MESSAGE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun getCurrentForegroundApp(): String {
        if (!hasUsageStatsPermission(this)) {
            Log.w(TAG, "Cannot get foreground app, permission denied.")
            return "Unknown App (No permission)"
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(time - TimeUnit.MINUTES.toMillis(1), time)
        val event = UsageEvents.Event()

        var lastForegroundEventTime: Long = 0
        var lastForegroundPackage: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp > lastForegroundEventTime) {
                    lastForegroundEventTime = event.timeStamp
                    lastForegroundPackage = event.packageName
                }
            }
        }

        return if (lastForegroundPackage != null) {
            getAppNameFromPackage(lastForegroundPackage, this)
        } else {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - TimeUnit.MINUTES.toMillis(1), time)
            if (stats != null && stats.isNotEmpty()) {
                val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
                if (sortedStats.isNotEmpty()) {
                    if (System.currentTimeMillis() - sortedStats[0].lastTimeUsed < TimeUnit.SECONDS.toMillis(10)) {
                        return getAppNameFromPackage(sortedStats[0].packageName!!, this)
                    }
                }
            }
            "Unknown App"
        }
    }

    private suspend fun generateWellbeingMessage() {
        val runner = this.modelRunner ?: run {
            Log.w(TAG, "ModelRunner not available for message generation.")
            updateText("Model not ready.")
            return
        }

        if (this.conversation == null) {
            this.conversation = runner.createConversation(SYSTEM_PROMPT)
        }

        val responseBuffer = StringBuilder()
        try {
            Log.d(TAG, "Generating new wellbeing message...")
            val currentApp = getCurrentForegroundApp()

            val percentage = appUsagePercentages.getOrPut(currentApp) {
                if (currentApp == "Unknown App" || currentApp == "Unknown App (No permission)") {
                    0
                } else {
                    randomGenerator.nextInt(101)
                }
            }
            updateAppUsageLabelAndProgress(currentApp, percentage)

            val userPrompt = "The user is currently using the app '$currentApp'. Their usage percentage for this app is $percentage%. Generate a small, concise, and witty response in the style of a Roman era knight to encourage them to reduce screen time, especially during the night, for peaceful sleep. Use not more than 30 words."
            this.conversation!!.generateResponse(userPrompt)
                .onEach { response ->
                    if (response is ai.liquid.leap.message.MessageResponse.Chunk) {
                        responseBuffer.append(response.text)
                    }
                }
                .onCompletion { throwable ->
                    if (throwable == null) {
                        val wellbeingMessage = responseBuffer.toString().trim()
                        // val finalMessage = "[$currentApp] $wellbeingMessage"
                        val finalMessage = "$wellbeingMessage"

                        Log.i(TAG, "Generated message for $currentApp: $wellbeingMessage")
                        updateText(finalMessage)
                        updateNotification("[$currentApp] ${wellbeingMessage.take(20)}...")
                    } else {
                        Log.e(TAG, "Message generation error (onCompletion)", throwable)
                        updateText("[$currentApp] Error generating reminder.")
                    }
                }
                .catch { e ->
                    Log.e(TAG, "Message generation exception (catch)", e)
                    updateText("[$currentApp] Error: Message generation failed.")
                }
                .collect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start message generation flow", e)
            val currentApp = getCurrentForegroundApp()
            val percentage = appUsagePercentages.getOrDefault(currentApp, 0)
            updateAppUsageLabelAndProgress(currentApp, percentage)
            updateText("[$currentApp] Error: Could not start generation.")
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getAppNameFromPackage(packageName: String, context: Context): String {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "App name not found for package: $packageName")
            packageName
        }
    }

    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = serviceScope.launch {
            while (isActive) {
                Log.d(TAG, "Data collection cycle started.")
                collectAndStoreUsageData()
                Log.d(TAG, "Data collection cycle finished. Next run in ${DATA_COLLECTION_INTERVAL_MS / 60000} minutes.")
                delay(DATA_COLLECTION_INTERVAL_MS)
            }
        }
    }

    private suspend fun collectAndStoreUsageData() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.MILLISECOND, (-DATA_QUERY_WINDOW_MS).toInt())
        val startTime = calendar.timeInMillis

        Log.d(TAG, "Querying usage events from ${formatTime(startTime)} to ${formatTime(endTime)}")

        try {
            val queryEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var eventsProcessed = 0

            while (queryEvents.hasNextEvent()) {
                queryEvents.getNextEvent(event)
                eventsProcessed++

                val appName: String
                var appUsageEvent: AppUsageEvent? = null

                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        appName = getAppNameFromPackage(event.packageName, this@FloatingIconService)
                        Log.d(TAG, "Event: FG - $appName at ${formatTime(event.timeStamp)}")
                        appUsageEvent = AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = appName,
                            usageTimeMillis = 0L, 
                            eventType = "APP_USAGE",
                            sessionOpenCount = 1 
                        )
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                         appName = getAppNameFromPackage(event.packageName, this@FloatingIconService)
                         Log.d(TAG, "Event: BG - $appName at ${formatTime(event.timeStamp)}")
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> { 
                        appName = "Screen on (unlocked)"
                         Log.d(TAG, "Event: SCREEN_ON at ${formatTime(event.timeStamp)}")
                        appUsageEvent = AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = appName,
                            usageTimeMillis = 0L,
                            eventType = "SYSTEM_EVENT",
                            sessionOpenCount = 1
                        )
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { 
                        appName = "Screen off (locked)"
                        Log.d(TAG, "Event: SCREEN_OFF at ${formatTime(event.timeStamp)}")
                        appUsageEvent = AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = appName,
                            usageTimeMillis = 0L,
                            eventType = "SYSTEM_EVENT",
                            sessionOpenCount = 0 
                        )
                    }
                    UsageEvents.Event.DEVICE_STARTUP -> {
                        appName = "Device boot"
                        Log.d(TAG, "Event: DEVICE_BOOT at ${formatTime(event.timeStamp)}")
                        appUsageEvent = AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = appName,
                            usageTimeMillis = 0L,
                            eventType = "SYSTEM_EVENT",
                            sessionOpenCount = 0
                        )
                    }
                     UsageEvents.Event.DEVICE_SHUTDOWN -> {
                        appName = "Device shutdown"
                         Log.d(TAG, "Event: DEVICE_SHUTDOWN at ${formatTime(event.timeStamp)}")
                        appUsageEvent = AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = appName,
                            usageTimeMillis = 0L,
                            eventType = "SYSTEM_EVENT",
                            sessionOpenCount = 0
                        )
                    }
                    else -> {}
                }

                appUsageEvent?.let {
                    try {
                        appUsageDao.insertEvent(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting event into database: ${it.appName}", e)
                    }
                }
            }
            Log.d(TAG, "Finished processing $eventsProcessed usage events for the window.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during collectAndStoreUsageData", e)
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    private fun updateText(text: String) {
        Handler(Looper.getMainLooper()).post {
            if (::floatingIconText.isInitialized) {
                if (text.isNotEmpty()) {
                    floatingIconText.text = text
                    floatingIconText.visibility = View.VISIBLE
                } else {
                    floatingIconText.visibility = View.GONE
                }
            }
        }
    }

    private fun updateAppUsageLabelAndProgress(appName: String, percentage: Int) {
        Handler(Looper.getMainLooper()).post {
            if (::appUsageLabelText.isInitialized && ::appUsageProgressBar.isInitialized) {
                val displayText = if (appName == "Unknown App" || appName == "Unknown App (No permission)") {
                    "App usage: N/A"
                } else {
                    "App usage: $appName - $percentage%"
                }
                appUsageLabelText.text = displayText
                appUsageLabelText.visibility = View.VISIBLE
                appUsageProgressBar.progress = percentage
                appUsageProgressBar.visibility = View.VISIBLE
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Floating Icon Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Digital Wellbeing Reminder")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_knight)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand received.")
        if (dataCollectionJob == null || !dataCollectionJob!!.isActive) {
            if (hasUsageStatsPermission(this)) {
                Log.d(TAG, "onStartCommand: Usage stats permission granted. Ensuring data collection is running.")
                startDataCollection()
            } else {
                Log.w(TAG, "onStartCommand: Usage stats permission NOT granted. Data collection will not start.")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        messageGenerationJob?.cancel()
        dataCollectionJob?.cancel()
        serviceScope.cancel()

        runBlocking {
            try {
                modelRunner?.unload()
                Log.i(TAG, "Model unloaded.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model", e)
            }
        }

        if (::floatingView.isInitialized && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
        if (::binView.isInitialized && binView.parent != null) {
            windowManager.removeView(binView)
        }
        stopForeground(true)
        Log.d(TAG, "Service destroyed.")
    }

    private fun showBinIcon() {
        if (!::binView.isInitialized) return
        Handler(Looper.getMainLooper()).post {
            if (binView.parent == null) {
                 try {
                     windowManager.addView(binView, binParams)
                 } catch (e: Exception) {
                     Log.e(TAG, "Error adding binView", e)
                 }
            }
            binView.visibility = View.VISIBLE
        }
    }

    private fun hideBinIcon() {
         if (!::binView.isInitialized) return
        Handler(Looper.getMainLooper()).post {
            binView.visibility = View.GONE
        }
    }

   private fun isViewOverlapping(view1: View, view2: View): Boolean {
        if (!::windowManager.isInitialized || !::floatingView.isInitialized || floatingView.parent == null || !::binView.isInitialized) {
            return false
        }

        val rect1 = Rect()
        view1.getHitRect(rect1)

        val location1 = IntArray(2)
        view1.getLocationOnScreen(location1)
        rect1.offsetTo(location1[0], location1[1])

        val binActualWidth = if (view2.width > 0) view2.width else binParams.width.takeIf { it > 0 } ?: 100
        val binActualHeight = if (view2.height > 0) view2.height else binParams.height.takeIf { it > 0 } ?: 100

        // Calculate bin's position on screen based on its gravity and x, y offsets in LayoutParams
        // For Gravity.BOTTOM | Gravity.START
        val binScreenX = binParams.x 
        val binScreenY = screenHeight - binActualHeight - binParams.y // y is from bottom

        val rect2Screen = Rect(
            binScreenX,
            binScreenY,
            binScreenX + binActualWidth,
            binScreenY + binActualHeight
        )
        // Log.d(TAG, "Rect1 (FloatingView): $rect1")
        // Log.d(TAG, "Rect2 (BinView Screen): $rect2Screen")
        // Log.d(TAG, "BinView LayoutParams: x=${binParams.x}, y=${binParams.y}, width=${binParams.width}, height=${binParams.height}")
        // Log.d(TAG, "Screen Dims: Height=$screenHeight, Width=$screenWidth")
        // Log.d(TAG, "BinView actual dims: width=$binActualWidth, height=$binActualHeight")

        return Rect.intersects(rect1, rect2Screen)
    }
}
