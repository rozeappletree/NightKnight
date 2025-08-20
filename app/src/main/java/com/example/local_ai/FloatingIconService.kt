package com.example.local_ai

import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.gson.registerLeapAdapters
import ai.liquid.leap.message.MessageResponse
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStats // Added import
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
import java.util.concurrent.TimeUnit

class FloatingIconService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingIconText: TextView
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

    // Database and Data Collection
    private lateinit var appUsageDao: AppUsageDao
    private var dataCollectionJob: Job? = null
    private val DATA_COLLECTION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15) // Collect every 15 mins
    private val DATA_QUERY_WINDOW_MS = TimeUnit.HOURS.toMillis(1)       // Query last 1 hour of events


    companion object {
        const val MODEL_SLUG = "lfm2-1.2b"
        const val QUANTIZATION_SLUG = "lfm2-1.2b-20250710-8da4w"
        const val SYSTEM_PROMPT = "You are a friendly assistant. Your goal is to persuade the user to stop using their mobile phone and focus on their digital wellbeing. Generate short, encouraging messages to help the user achieve this."
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

        // Initialize Database DAO
        appUsageDao = AppDatabase.getDatabase(this).appUsageDao()

        // Start Data Collection if permission is granted
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
                params.x = initialX // Keep X coordinate constant
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
            val modelToUse = LeapDownloadableModel.resolve(MODEL_SLUG, QUANTIZATION_SLUG)
            if (modelToUse == null) {
                throw RuntimeException("Model $QUANTIZATION_SLUG not found in Leap Model Library!")
            }

            val modelDownloader = LeapModelDownloader(applicationContext)
            val checkingStatusMsg = "Checking download status..."
            onStatusChange(checkingStatusMsg)
            updateText(checkingStatusMsg)

            if (modelDownloader.queryStatus(modelToUse).type != LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED) {
                val requestingDownloadMsg = "Requesting model download..."
                onStatusChange(requestingDownloadMsg)
                Log.d(TAG, "MODEL DOWNLOAD CODE IS BEING TRIGGERED")
                modelDownloader.requestDownloadModel(modelToUse)
                var isModelAvailable = false
                while (!isModelAvailable) {
                    val status = modelDownloader.queryStatus(modelToUse)
                    var currentStatusMsg = ""
                    when (status.type) {
                        LeapModelDownloader.ModelDownloadStatusType.NOT_ON_LOCAL -> {
                            currentStatusMsg = "Model not downloaded. Waiting..."
                        }
                        LeapModelDownloader.ModelDownloadStatusType.DOWNLOAD_IN_PROGRESS -> {
                            currentStatusMsg = "Downloading: ${String.format("%.2f", status.progress * 100.0)}%"
                        }
                        LeapModelDownloader.ModelDownloadStatusType.DOWNLOADED -> {
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
            while (isActive) { // Use isActive to respect cancellation
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
        // Query for events in the last 1 minute.
        val usageEvents = usageStatsManager.queryEvents(time - TimeUnit.MINUTES.toMillis(1), time)
        val event = UsageEvents.Event() // Re-use event object

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
            // Fallback: Check most recent app from usage stats (less real-time)
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - TimeUnit.MINUTES.toMillis(1), time)
            if (stats != null && stats.isNotEmpty()) {
                val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
                if (sortedStats.isNotEmpty()) {
                    // Check if the most recent app was used very recently (e.g., within last 10 seconds)
                    if (System.currentTimeMillis() - sortedStats[0].lastTimeUsed < TimeUnit.SECONDS.toMillis(10)) {
                        return getAppNameFromPackage(sortedStats[0].packageName!!, this)
                    }
                }
            }
            "Unknown App" // Default if no foreground app is reliably found
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
            val currentApp = getCurrentForegroundApp() // Get current app

            this.conversation!!.generateResponse("Give me a digital wellbeing tip.")
                .onEach { response ->
                    if (response is MessageResponse.Chunk) {
                        responseBuffer.append(response.text)
                    }
                }
                .onCompletion { throwable ->
                    if (throwable == null) {
                        val wellbeingMessage = responseBuffer.toString().trim()
                        val finalMessage = "[$currentApp] $wellbeingMessage"
                        Log.i(TAG, "Generated message for $currentApp: $wellbeingMessage")
                        updateText(finalMessage)
                        // Adjust take() if needed for notification length
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
            val currentApp = getCurrentForegroundApp() // Get current app even for this error
            updateText("[$currentApp] Error: Could not start generation.")
        }
    }

    // --- Data Collection Logic ---
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
            packageName // Fallback to package name
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
            val event = UsageEvents.Event() // Re-use object
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
                    else -> {
                        // Log.v(TAG, "Unhandled event type: ${event.eventType} for package ${event.packageName}")
                    }
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
    // --- End Data Collection Logic ---


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
            .setSmallIcon(R.mipmap.ic_launcher)
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
                 try { windowManager.addView(binView, binParams) } catch (e: Exception) { Log.e(TAG, "Error adding binView", e)}
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
        if (!::windowManager.isInitialized || view1.parent == null || view2.parent == null && view2.height == 0 && view2.width == 0) {
            return false
        }
        val rect1 = Rect()
        view1.getHitRect(rect1)

        val location1 = IntArray(2)
        view1.getLocationOnScreen(location1)
        rect1.offsetTo(location1[0], location1[1])

        val binScreenX = binParams.x
        val binHeight = if (view2.height == 0) 100 else view2.height
        val binWidth = if (view2.width == 0) 100 else view2.width
        val binScreenY = screenHeight - binHeight - binParams.y

        val rect2Screen = Rect(
            binScreenX,
            binScreenY,
            binScreenX + binWidth,
            binScreenY + binHeight
        )
        return Rect.intersects(rect1, rect2Screen)
    }
}
