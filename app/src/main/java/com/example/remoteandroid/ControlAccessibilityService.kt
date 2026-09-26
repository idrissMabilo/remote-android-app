package com.example.remoteandroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Exécute les commandes envoyées par le PC : tap, swipe, retour, accueil,
 * récents. Fonctionne sans root grâce à l'API AccessibilityService
 * (dispatchGesture / performGlobalAction), officiellement supportée par Android.
 *
 * L'utilisateur doit activer ce service manuellement dans
 * Paramètres > Accessibilité (c'est une exigence de sécurité d'Android :
 * impossible de l'activer par programmation).
 */
class ControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ControlAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("ControlA11yService", "Service d'accessibilité connecté")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun handleCommand(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "tap" -> tap(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                "swipe" -> swipe(
                    obj.getDouble("x1").toFloat(), obj.getDouble("y1").toFloat(),
                    obj.getDouble("x2").toFloat(), obj.getDouble("y2").toFloat(),
                    obj.optLong("durationMs", 300L)
                )
                "long_press" -> longPress(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "text" -> typeText(obj.getString("value"))
                else -> Log.w("ControlA11yService", "Commande inconnue: $json")
            }
        } catch (e: Exception) {
            Log.e("ControlA11yService", "Commande invalide: $json", e)
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Injecte du texte dans le champ actuellement focalisé, via l'arbre
     * d'accessibilité (ACTION_SET_TEXT). Fonctionne pour la plupart des
     * champs de texte standards, sans root.
     */
    private fun typeText(value: String) {
        val node = findFocusedEditableNode(rootInActiveWindow) ?: return
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findFocusedEditableNode(
        node: android.view.accessibility.AccessibilityNodeInfo?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = findFocusedEditableNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
