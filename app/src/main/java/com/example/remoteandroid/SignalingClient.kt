package com.example.remoteandroid

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Petite enveloppe autour d'OkHttp WebSocket pour se connecter au serveur relais.
 * Utilisée à la fois pour envoyer les frames vidéo (binaire) et recevoir les
 * commandes de contrôle (texte JSON).
 */
class SignalingClient(
    private val serverUrl: String,
    private val room: String,
    private val secret: String,
    private val role: String, // "phone"
    private val onText: (String) -> Unit,
    private val onOpen: () -> Unit = {},
    private val onClosed: (Int, String) -> Unit = { _, _ -> }
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // connexion longue durée
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val url = "$serverUrl?role=$role&room=$room&secret=$secret"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("SignalingClient", "Connecté au relais")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("SignalingClient", "Connexion fermée: $code $reason")
                onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalingClient", "Erreur de connexion", t)
                onClosed(-1, t.message ?: "erreur inconnue")
            }
        })
    }

    fun sendBinary(bytes: ByteArray) {
        webSocket?.send(ByteString.of(*bytes))
    }

    fun sendText(text: String) {
        webSocket?.send(text)
    }

    fun close() {
        webSocket?.close(1000, "fermeture normale")
        client.dispatcher.executorService.shutdown()
    }
}
