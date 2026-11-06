package com.shobi.fieldtracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Sends waiting locations and a pending shift end. Safe to call from any background thread. */
object Uploader {

    enum class Result { OK, OFFLINE, UNPAIRED, ENDED_BY_SERVER, ERROR }

    private const val BATCH = 200
    private val lock = Any()

    fun sync(context: Context): Result {
        synchronized(lock) {
            return syncLocked(context)
        }
    }

    private fun removeFirstTaskAction(prefs: Prefs) {
        val list = prefs.pendingTaskList()
        val rest = JSONArray()
        for (i in 1 until list.length()) rest.put(list.get(i))
        prefs.pendingTaskActions = rest.toString()
    }

    private fun removeFirstPunch(prefs: Prefs) {
        val list = prefs.pendingPunchList()
        val rest = JSONArray()
        for (i in 1 until list.length()) rest.put(list.get(i))
        prefs.pendingPunches = rest.toString()
    }

    private fun syncLocked(context: Context): Result {
        val prefs = Prefs(context)
        val queue = PointQueue.get(context)
        if (!prefs.isPaired) return Result.OK
        var serverOnShift: Boolean? = null
        try {
            // Attendance punches first, oldest first.
            while (true) {
                val pending = prefs.pendingPunchList()
                if (pending.length() == 0) break
                val punch = pending.getJSONObject(0)
                try {
                    val reply = Api.post(prefs, Api.PUNCH, punch)
                    removeFirstPunch(prefs)
                    prefs.saveProfile(reply)
                    reply.optString("note").takeIf { it.isNotEmpty() }?.let { prefs.notice = it }
                } catch (e: ApiException) {
                    if (e.httpCode == 401) throw e
                    // Refused by Odoo (e.g. already punched in): drop it and show why.
                    removeFirstPunch(prefs)
                    prefs.notice = e.message ?: "Punch refused."
                    try {
                        prefs.saveProfile(Api.post(prefs, Api.STATUS, JSONObject()))
                    } catch (ignored: ApiException) {
                    }
                }
            }
            // Inbox actions (read, start, complete), oldest first.
            var sentTaskAction = false
            while (true) {
                val pending = prefs.pendingTaskList()
                if (pending.length() == 0) break
                val action = pending.getJSONObject(0)
                val path = when (action.optString("action")) {
                    "read" -> Api.TASK_READ
                    "start" -> Api.TASK_START
                    else -> Api.TASK_COMPLETE
                }
                try {
                    val reply = Api.post(prefs, path, action)
                    removeFirstTaskAction(prefs)
                    prefs.saveProfile(reply)
                } catch (e: ApiException) {
                    if (e.httpCode == 401) throw e
                    removeFirstTaskAction(prefs)
                    prefs.notice = e.message ?: "Not accepted by Odoo."
                }
                sentTaskAction = true
            }
            if (sentTaskAction || System.currentTimeMillis() - prefs.tasksFetchedAt > 60_000L) {
                val reply = Api.post(prefs, Api.TASKS, JSONObject())
                reply.optJSONArray("tasks")?.let { prefs.tasksJson = it.toString() }
                prefs.tasksFetchedAt = System.currentTimeMillis()
                prefs.saveProfile(reply)
            }
            while (true) {
                val batch = queue.peek(BATCH)
                if (batch.isEmpty()) break
                val arr = JSONArray()
                batch.forEach { arr.put(it.json) }
                val reply = Api.post(prefs, Api.POINTS, JSONObject().put("points", arr))
                queue.delete(batch.map { it.id })
                prefs.lastSentAt = System.currentTimeMillis()
                serverOnShift = reply.optBoolean("on_shift", true)
            }
            if (prefs.pendingEndAt > 0) {
                val end = JSONObject().put("t", prefs.pendingEndAt)
                if (prefs.pendingEndOdometer >= 0) end.put("odometer", prefs.pendingEndOdometer)
                Api.post(prefs, Api.SHIFT_END, end)
                prefs.pendingEndAt = 0L
                prefs.pendingEndOdometer = -1
                serverOnShift = null
            }
            prefs.lastError = ""
            if (serverOnShift == false && prefs.onShift) {
                prefs.onShift = false
                prefs.notice = context.getString(R.string.ended_by_server)
                return Result.ENDED_BY_SERVER
            }
            return Result.OK
        } catch (e: ApiException) {
            if (e.httpCode == 401) {
                queue.clear()
                prefs.unpair()
                prefs.notice = context.getString(R.string.unpaired_by_server)
                return Result.UNPAIRED
            }
            prefs.lastError = e.message ?: "Server error."
            return Result.ERROR
        } catch (e: IOException) {
            val waiting = queue.count()
            prefs.lastError = if (waiting > 0) {
                "No internet. $waiting locations waiting to send."
            } else {
                "No internet. Will retry automatically."
            }
            return Result.OFFLINE
        }
    }
}
