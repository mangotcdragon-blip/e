package com.customautocorrect.keyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Rule(val from: String, val to: String)

object DictionaryStore {
    private const val PREFS_NAME = "autocorrect_prefs"
    private const val KEY_RULES = "rules_json"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRules(context: Context): List<Rule> {
        val json = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        return try {
            parseJson(json)
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun saveRules(context: Context, rules: List<Rule>) {
        prefs(context).edit().putString(KEY_RULES, toJson(rules)).apply()
    }

    fun toJson(rules: List<Rule>): String {
        val arr = JSONArray()
        for (r in rules) {
            val obj = JSONObject()
            obj.put("from", r.from)
            obj.put("to", r.to)
            arr.put(obj)
        }
        return arr.toString(2)
    }

    /** Accepts either an array of {from, to} objects, or a plain {word: replacement} object. */
    fun parseJson(text: String): List<Rule> {
        val trimmed = text.trim()
        val result = mutableListOf<Rule>()
        when {
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val from = obj.optString("from", "").trim()
                    val to = obj.optString("to", "")
                    if (from.isNotEmpty()) result.add(Rule(from, to))
                }
            }
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (k.trim().isNotEmpty()) result.add(Rule(k, v))
                }
            }
            else -> throw JSONException("Expected a JSON array of rules or a {word: replacement} object")
        }
        return result
    }

    fun upsert(existing: List<Rule>, rule: Rule): List<Rule> {
        val idx = existing.indexOfFirst { it.from.equals(rule.from, ignoreCase = true) }
        return if (idx >= 0) {
            existing.toMutableList().also { it[idx] = rule }
        } else {
            existing + rule
        }
    }

    fun mergeAll(existing: List<Rule>, incoming: List<Rule>): List<Rule> {
        var result = existing
        for (r in incoming) result = upsert(result, r)
        return result
    }
}
