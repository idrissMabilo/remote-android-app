package com.example.remoteandroid

import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etRoomCode: EditText
    private lateinit var etSecret: EditText

    private val screenCaptureRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etServerUrl = findViewById(R.id.etServerUrl)
        etRoomCode = findViewById(R.id.etRoomCode)
        etSecret = findViewById(R.id.etSecret)

        findViewById<Button>(R.id.btnGenerateCode).setOnClickListener {
            etRoomCode.setText(generateRoomCode())
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Active 'Remote Android' dans la liste des services d'accessibilité",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { onStopClicked() }

        updateStatus()
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sans caractères ambigus
        val random = SecureRandom()
        return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun onStartClicked() {
        val serverUrl = etServerUrl.text.toString().trim()
        val room = etRoomCode.text.toString().trim()
        val secret = etSecret.text.toString().trim()

        if (TextUtils.isEmpty(serverUrl) || TextUtils.isEmpty(room) || TextUtils.isEmpty(secret)) {
            Toast.makeText(this, "Remplis l'URL du serveur, le code et le secret", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Active d'abord le service d'accessibilité (étape 1)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Sauvegarde temporaire pour la callback de permission
        pendingServerUrl = serverUrl
        pendingRoom = room
        pendingSecret = secret

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), screenCaptureRequestCode)
    }

    private var pendingServerUrl: String? = null
    private var pendingRoom: String? = null
    private var pendingSecret: String? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenCaptureRequestCode && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, pendingServerUrl)
                putExtra(ScreenCaptureService.EXTRA_ROOM, pendingRoom)
                putExtra(ScreenCaptureService.EXTRA_SECRET, pendingSecret)
            }
            startForegroundService(serviceIntent)
            tvStatus.text = "Statut : partage d'écran démarré (room: $pendingRoom)"
        } else {
            Toast.makeText(this, "Autorisation de capture refusée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onStopClicked() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        tvStatus.text = "Statut : arrêté"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, ControlAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun updateStatus() {
        tvStatus.text = if (isAccessibilityServiceEnabled())
            "Statut : service d'accessibilité activé, prêt à démarrer"
        else
            "Statut : active d'abord le service d'accessibilité"
    }
}
