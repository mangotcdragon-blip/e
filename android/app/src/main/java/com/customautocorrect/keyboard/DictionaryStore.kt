package com.customautocorrect.keyboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

data class Rule(val from: String, val to: String)

private class DictionaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE rules (" +
                "rule_key TEXT PRIMARY KEY, " +
                "from_word TEXT NOT NULL, " +
                "to_word TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS rules")
        onCreate(db)
    }

    companion object {
        private const val DB_NAME = "autocorrect.db"
        private const val DB_VERSION = 1

        @Volatile private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * SQLite-backed dictionary store. Indexed lookups keep autocorrect fast regardless of
 * dictionary size, and import/export stream row-by-row so a 600k-entry file never has to
 * be held fully in memory as JSON.
 */
object DictionaryStore {

    fun lookup(context: Context, word: String): String? {
        val db = DictionaryDb.get(context).readableDatabase
        db.query(
            "rules", arrayOf("to_word"), "rule_key = ?", arrayOf(word.lowercase()),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun count(context: Context, search: String = ""): Int {
        val db = DictionaryDb.get(context).readableDatabase
        val (whereClause, args) = searchClause(search)
        db.rawQuery("SELECT COUNT(*) FROM rules $whereClause", args).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    fun page(context: Context, offset: Int, limit: Int, search: String = ""): List<Rule> {
        val db = DictionaryDb.get(context).readableDatabase
        val (whereClause, args) = searchClause(search)
        val result = mutableListOf<Rule>()
        db.rawQuery(
            "SELECT from_word, to_word FROM rules $whereClause ORDER BY rule_key LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(Rule(cursor.getString(0), cursor.getString(1)))
            }
        }
        return result
    }

    private fun searchClause(search: String): Pair<String, Array<String>> {
        if (search.isBlank()) return "" to emptyArray()
        return "WHERE rule_key LIKE ?" to arrayOf("%${search.trim().lowercase()}%")
    }

    fun upsert(context: Context, from: String, to: String) {
        val db = DictionaryDb.get(context).writableDatabase
        val values = ContentValues().apply {
            put("rule_key", from.lowercase())
            put("from_word", from)
            put("to_word", to)
        }
        db.insertWithOnConflict("rules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(context: Context, from: String) {
        val db = DictionaryDb.get(context).writableDatabase
        db.delete("rules", "rule_key = ?", arrayOf(from.lowercase()))
    }

    fun clear(context: Context) {
        val db = DictionaryDb.get(context).writableDatabase
        db.execSQL("DELETE FROM rules")
    }

    /**
     * Streams a JSON import (either an array of {from, to} objects, or a flat
     * {word: replacement} object) straight into SQLite inside one transaction, without ever
     * materializing the whole file as a JSON tree or a List<Rule>. Safe for very large files.
     * Must be called off the main thread.
     */
    fun importStream(context: Context, input: InputStream): Int {
        val db = DictionaryDb.get(context).writableDatabase
        var count = 0
        JsonReader(input.reader()).use { reader ->
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO rules (rule_key, from_word, to_word) VALUES (?, ?, ?)"
                )
                fun insert(from: String, to: String) {
                    if (from.isBlank()) return
                    stmt.clearBindings()
                    stmt.bindString(1, from.lowercase())
                    stmt.bindString(2, from)
                    stmt.bindString(3, to)
                    stmt.executeInsert()
                    count++
                }

                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var from: String? = null
                            var to: String? = null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "from" -> from = reader.nextString()
                                    "to" -> to = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (from != null && to != null) insert(from, to)
                        }
                        reader.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val from = reader.nextName()
                            val to = reader.nextString()
                            insert(from, to)
                        }
                        reader.endObject()
                    }
                    else -> throw IllegalArgumentException(
                        "Expected a JSON array of rules or a {word: replacement} object"
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return count
    }

    /**
     * Streams all rules out as JSON without holding them all in memory at once.
     * Must be called off the main thread.
     */
    fun exportStream(context: Context, output: OutputStream) {
        val db = DictionaryDb.get(context).readableDatabase
        JsonWriter(OutputStreamWriter(output)).use { writer ->
            writer.setIndent("  ")
            writer.beginArray()
            db.rawQuery("SELECT from_word, to_word FROM rules ORDER BY rule_key", null).use { cursor ->
                while (cursor.moveToNext()) {
                    writer.beginObject()
                    writer.name("from").value(cursor.getString(0))
                    writer.name("to").value(cursor.getString(1))
                    writer.endObject()
                }
            }
            writer.endArray()
        }
    }
}
