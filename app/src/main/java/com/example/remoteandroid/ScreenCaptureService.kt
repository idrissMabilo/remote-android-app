package com.example.remoteandroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Capture l'écran du téléphone (sans root, API officielle MediaProjection),
 * compresse chaque frame en JPEG et l'envoie au serveur relais.
 * Reçoit aussi les commandes de contrôle (JSON) et les transmet au
 * ControlAccessibilityService pour exécution.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_ROOM = "room"
        const val EXTRA_SECRET = "secret"

        // fréquence d'envoi des images (compromis latence / bande passante / CPU)
        const val TARGET_FPS = 12
        const val JPEG_QUALITY = 55
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var signalingClient: SignalingClient? = null
    private var lastFrameTimeMs = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
        val room = intent.getStringExtra(EXTRA_ROOM) ?: return START_NOT_STICKY
        val secret = intent.getStringExtra(EXTRA_SECRET) ?: return START_NOT_STICKY

        if (resultData == null) return START_NOT_STICKY

        connectSignaling(serverUrl, room, secret)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, null)

        startCapture()
        return START_NOT_STICKY
    }

    private fun connectSignaling(serverUrl: String, room: String, secret: String) {
        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            room = room,
            secret = secret,
            role = "phone",
            onText = { text -> handleControlMessage(text) },
            onOpen = { Log.i("ScreenCaptureService", "Signalisation connectée") },
            onClosed = { code, reason -> Log.i("ScreenCaptureService", "Signalisation fermée: $code $reason") }
        )
        signalingClient?.connect()
    }

    private fun handleControlMessage(text: String) {
        // Transmet la commande au service d'accessibilité (qui exécute réellement le geste)
        ControlAccessibilityService.instance?.handleCommand(text)
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // On réduit la résolution pour limiter la bande passante / le CPU
        val scale = 0.6
        screenWidth = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        screenHeight = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
        screenDensity = metrics.densityDpi

        // On informe le PC de la résolution réelle de l'écran pour que les
        // coordonnées des clics soient converties correctement.
        val info = JSONObject()
            .put("type", "screen_info")
            .put("realWidth", metrics.widthPixels)
            .put("realHeight", metrics.heightPixels)
        signalingClient?.sendText(info.toString())

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteAndroidCapture",
            screenWidth, screenHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            val minIntervalMs = 1000L / TARGET_FPS
            val image = reader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener
            if (now - lastFrameTimeMs < minIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTimeMs = now
            try {
                val jpeg = imageToJpeg(image)
                signalingClient?.sendBinary(jpeg)
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "Erreur d'encodage frame", e)
            } finally {
                image.close()
            }
        }, null)
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        cropped.recycle()
        return out.toByteArray()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Partage d'écran actif")
            .setContentText("Ton écran est actuellement accessible depuis ton PC.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Partage d'écran", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        signalingClient?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
