package app.hearts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HeartService : Service() {
    companion object {
        const val ACTION_START = "app.hearts.START"
        const val ACTION_STOP = "app.hearts.STOP"
        const val ACTION_SEND = "app.hearts.SEND"
        const val ACTION_HEART = "app.hearts.HEART"
        const val ACTION_STATE = "app.hearts.STATE"
        private const val API = "https://hearts-api.dark-aurelien-poke-worker.workers.dev"
        private const val CHANNEL_SERVICE = "heart_connection"
        private const val CHANNEL_HEARTS = "heart_arrivals"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private val pendingHearts = ArrayDeque<String>()
    private var reconnectAttempt = 0
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopping = true; socket?.close(1000, "stop"); stopSelf() }
            ACTION_SEND -> {
                val x = intent.getFloatExtra("x", .5f).coerceIn(0f, 1f)
                val y = intent.getFloatExtra("y", .5f).coerceIn(0f, 1f)
                val message = JSONObject().put("type", "heart").put("x", x).put("y", y).toString()
                if (socket?.send(message) != true) synchronized(pendingHearts) {
                    if (pendingHearts.size >= 8) pendingHearts.removeFirst()
                    pendingHearts.addLast(message)
                }
            }
            else -> {
                startForeground(1001, serviceNotification("Connexion…"))
                if (socket == null) authenticateAndConnect()
            }
        }
        return START_STICKY
    }

    private fun authenticateAndConnect() {
        val password = SecretStore(this).read()
        if (password.isNullOrEmpty()) {
            updateState("Mot de passe requis")
            stopSelf()
            return
        }
        val body = JSONObject().put("password", password).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$API/auth").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = scheduleReconnect()
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (it.code == 401) SecretStore(this@HeartService).clear()
                        updateState(if (it.code == 401) "Mot de passe incorrect" else "Connexion impossible")
                        scheduleReconnect()
                        return
                    }
                    val token = JSONObject(it.body?.string().orEmpty()).optString("token")
                    if (token.isEmpty()) { scheduleReconnect(); return }
                    openSocket(token)
                }
            }
        })
    }

    private fun openSocket(token: String) {
        val request = Request.Builder().url("${API.replace("https://", "wss://")}/room?token=$token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                synchronized(pendingHearts) {
                    while (pendingHearts.isNotEmpty()) {
                        if (!webSocket.send(pendingHearts.first())) break
                        pendingHearts.removeFirst()
                    }
                }
                updateState("Connecté • en attente")
                notifyService("Connecté • en attente d'un cœur")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val data = try { JSONObject(text) } catch (_: Exception) { return }
                when (data.optString("type")) {
                    "heart" -> {
                        val x = data.optDouble("x", .5).takeIf { it.isFinite() && it in 0.0..1.0 } ?: .5
                        val y = data.optDouble("y", .5).takeIf { it.isFinite() && it in 0.0..1.0 } ?: .5
                        receiveHeart(x.toFloat(), y.toFloat())
                    }
                    "presence" -> {
                        val label = if (data.optInt("count") >= 2) "Vous êtes deux" else "En attente de l'autre"
                        updateState(label); notifyService(label)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null; updateState("Reconnexion…"); scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (!stopping) scheduleReconnect()
            }
        })
    }

    private fun receiveHeart(x: Float, y: Float) {
        sendBroadcast(Intent(ACTION_HEART).setPackage(packageName).putExtra("x", x).putExtra("y", y))
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 55, 110), -1))
        ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 65).apply {
            startTone(ToneGenerator.TONE_PROP_ACK, 220)
            android.os.Handler(mainLooper).postDelayed({ release() }, 350)
        }
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_HEARTS)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Un cœur vient d'arriver")
            .setContentText("Elle pense à toi ♥")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    private fun scheduleReconnect() {
        if (stopping) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delay = (1L shl reconnectAttempt) * 1000L
        android.os.Handler(mainLooper).postDelayed({ if (!stopping && socket == null) authenticateAndConnect() }, delay)
    }

    private fun updateState(value: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("state", value))
    }

    private fun serviceNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Hearts actif")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun notifyService(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1001, serviceNotification(text))
    }

    private fun createChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Connexion en arrière-plan", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_HEARTS, "Cœurs reçus", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 70, 55, 110)
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        })
    }

    override fun onDestroy() {
        stopping = true
        socket?.close(1000, "service stopped")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
