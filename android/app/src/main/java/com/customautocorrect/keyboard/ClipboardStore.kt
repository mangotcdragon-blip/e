package com.customautocorrect.keyboard

import android.content.Context
import org.json.JSONArray

object ClipboardStore {
    private const val PREFS_NAME = "clipboard_prefs"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_ITEMS = 30

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<String> {
        val json = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun addEntry(context: Context, text: String) {
        if (text.isBlank()) return
        val current = load(context).toMutableList()
        current.remove(text)
        current.add(0, text)
        while (current.size > MAX_ITEMS) current.removeAt(current.size - 1)
        save(context, current)
    }

    fun removeEntry(context: Context, text: String) {
        val current = load(context).toMutableList()
        current.remove(text)
        save(context, current)
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }
}
