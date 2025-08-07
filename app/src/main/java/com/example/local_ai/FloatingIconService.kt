package com.example.local_ai

import ai.liquid.leap.Conversation
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.downloader.LeapDownloadableModel
import ai.liquid.leap.downloader.LeapModelDownloader
import ai.liquid.leap.gson.registerLeapAdapters
import ai.liquid.leap.message.MessageResponse
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private val gson = GsonBuilder().registerLeapAdapters().create() // LeapGson.get() could also be used if preferred

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var appPackageManager: PackageManager
    private var appNameUpdateJob: Job? = null

    companion object {
        const val MODEL_SLUG = "lfm2-1.2b"
        const val QUANTIZATION_SLUG = "lfm2-1.2b-20250710-8da4w"
        const val SYSTEM_PROMPT = "You are a friendly assistant. Your goal is to persuade the user to stop using their mobile phone and focus on their digital wellbeing. Generate short, encouraging messages to help the user achieve this."
        const val MESSAGE_REFRESH_INTERVAL_MS = 5000L
        const val NOTIFICATION_CHANNEL_ID = "FloatingIconServiceChannel"
        const val NOTIFICATION_ID = 1
        const val TAG = "FloatingIconService"
        const val APP_CHECK_INTERVAL_MS = 1000L // Interval to check foreground app
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        setupWindowManagerAndFloatingView()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing service..."))

        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        appPackageManager = packageManager

        Log.d(TAG, "Service created. Starting model loading and message generation.")
        serviceScope.launch {
            loadModelAndStartMessageGeneration(
                onStatusChange = { status ->
                    Log.i(TAG, "Model loading status: $status")
                    updateNotification("Model: $status")
                    // updateText(status) // Model loading statuses will be shown, then overwritten by app name
                },
                onError = { error ->
                    Log.e(TAG, "Model loading failed", error)
                    updateNotification("Error: Model load failed")
                    updateText("Error loading model.") // Show critical errors
                }
            )
        }
        startForegroundAppUpdater()
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

        updateText("Loading...") // Initial text

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

            val modelLoadedMsg = "Model loaded." // Simplified from "Model loaded. Starting message generation."
            onStatusChange(modelLoadedMsg)
            updateText(modelLoadedMsg) // Text will soon be replaced by app name
            startPeriodicMessageGeneration()
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadModelAndStartMessageGeneration", e)
            onError(e)
        }
    }

    private fun startPeriodicMessageGeneration() {
        messageGenerationJob?.cancel()
        messageGenerationJob = serviceScope.launch {
            while (true) {
                generateWellbeingMessage() // This will update notifications
                delay(MESSAGE_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun generateWellbeingMessage() {
        val runner = this.modelRunner ?: run {
            Log.w(TAG, "ModelRunner not available for message generation.")
            return
        }

        if (this.conversation == null) {
            this.conversation = runner.createConversation(SYSTEM_PROMPT)
        }

        val responseBuffer = StringBuilder()
        try {
            Log.d(TAG, "Generating new wellbeing message...")

            this.conversation!!.generateResponse("Give me a digital wellbeing tip.")
                .onEach { response ->
                    if (response is MessageResponse.Chunk) {
                        responseBuffer.append(response.text)
                    }
                }
                .onCompletion { throwable ->
                    if (throwable == null) {
                        val message = responseBuffer.toString().trim()
                        Log.i(TAG, "Generated message: $message")
                        // updateText(message) // Removed: Floating text shows app name
                        updateNotification("Reminder: ${message.take(20)}...")
                    } else {
                        Log.e(TAG, "Message generation error (onCompletion)", throwable)
                        // updateText("Error generating reminder.") // Removed
                    }
                }
                .catch { e ->
                    Log.e(TAG, "Message generation exception (catch)", e)
                    // updateText("Error: Message generation failed.") // Removed
                }
                .collect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start message generation flow", e)
            // updateText("Error: Could not start generation.") // Removed
        }
    }

    private fun startForegroundAppUpdater() {
        appNameUpdateJob?.cancel()
        appNameUpdateJob = serviceScope.launch(Dispatchers.IO) {
            while (true) {
                val currentApp = getForegroundAppName()
                updateText(currentApp)
                delay(APP_CHECK_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("WrongConstant") // UsageEvents.Event constants are fine
    private fun getForegroundAppName(): String {
        var currentForegroundApp = "" // Default to empty or a placeholder
        val time = System.currentTimeMillis()
        // Query for events in a recent window (e.g., last 10 seconds)
        val usageEvents = usageStatsManager.queryEvents(time - 10 * 1000, time)

        var lastForegroundPackageName: String? = null
        var lastForegroundTimeStamp: Long = 0

        val event = UsageEvents.Event() // Re-use event object for efficiency

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // Check if this event is more recent than the last one found
                if (event.timeStamp > lastForegroundTimeStamp) {
                    lastForegroundTimeStamp = event.timeStamp
                    lastForegroundPackageName = event.packageName
                }
            }
        }

        if (lastForegroundPackageName != null) {
            try {
                // Ensure lastForegroundPackageName is not null before using !!
                val appInfo = appPackageManager.getApplicationInfo(lastForegroundPackageName!!, 0)
                currentForegroundApp = appPackageManager.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "App package not found: $lastForegroundPackageName", e)
                // Ensure lastForegroundPackageName is not null before using !!
                currentForegroundApp = lastForegroundPackageName!! // Fallback to package name
            }
        } else {
             // currentForegroundApp remains "" or could be "Unknown"
             // This can happen if no foreground event is found or if permission is missing.
        }
        return currentForegroundApp.ifEmpty { "..." } // Provide a placeholder for empty string
    }

    private fun updateText(text: String) {
        Handler(Looper.getMainLooper()).post {
            if (::floatingIconText.isInitialized) {
                if (text.isNotEmpty()) {
                    floatingIconText.text = text
                    floatingIconText.visibility = View.VISIBLE
                } else {
                    // If text is empty, you might want to hide it or show a placeholder
                    floatingIconText.text = "..." // Default placeholder if app name is empty
                    floatingIconText.visibility = View.VISIBLE
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
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        messageGenerationJob?.cancel()
        appNameUpdateJob?.cancel() // Cancel the app name updater job
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
