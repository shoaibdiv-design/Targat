package com.shobi.fieldtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Locations waiting to be sent. Kept on the phone so nothing is lost without internet. */
class PointQueue private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "points.db", null, 1) {

    data class Item(val id: Long, val json: JSONObject)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, spd REAL, bat INTEGER, mock INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(t: Long, lat: Double, lon: Double, acc: Float, spdKmh: Float, battery: Int, mock: Boolean) {
        val v = ContentValues().apply {
            put("t", t); put("lat", lat); put("lon", lon); put("acc", acc)
            put("spd", spdKmh); put("bat", battery); put("mock", if (mock) 1 else 0)
        }
        writableDatabase.insert("points", null, v)
    }

    @Synchronized
    fun peek(limit: Int): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery(
            "SELECT id, t, lat, lon, acc, spd, bat, mock FROM points ORDER BY t LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val j = JSONObject()
                j.put("t", c.getLong(1)); j.put("lat", c.getDouble(2)); j.put("lon", c.getDouble(3))
                j.put("acc", c.getDouble(4)); j.put("spd", c.getDouble(5)); j.put("bat", c.getInt(6))
                j.put("mock", c.getInt(7) == 1)
                out.add(Item(c.getLong(0), j))
            }
        }
        return out
    }

    @Synchronized
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("points", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM points", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("points", null, null)
    }

    companion object {
        @Volatile private var instance: PointQueue? = null
        fun get(context: Context): PointQueue =
            instance ?: synchronized(this) { instance ?: PointQueue(context).also { instance = it } }
    }
}
