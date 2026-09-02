package com.dailytools.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailytools.calculator.data.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore(name = "gallery_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LAST_SOURCE = stringPreferencesKey("last_source")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }

    val lastSource: Flow<Source> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SOURCE]?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.E621
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setLastSource(source: Source) {
        context.dataStore.edit { it[Keys.LAST_SOURCE] = source.name }
    }
}
