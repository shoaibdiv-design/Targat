package com.shobi.fieldtracker

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Inbox tab: list of messages and tasks, and the detail screen for one of them. */
class InboxUi(
    private val act: MainActivity,
    private val prefs: Prefs,
    private val listBox: LinearLayout,
    private val detailBox: LinearLayout,
) {
    /** Id of the open message/task, 0 when the list is shown. */
    var openId: Long = 0L
        private set

    private var listSignature = ""
    private val checked = HashMap<Long, MutableSet<Long>>()
    private val notes = HashMap<Long, String>()

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(act, id)

    private fun text(value: CharSequence, sizeSp: Float = 14f, bold: Boolean = false, colorRes: Int? = null): TextView {
        val tv = TextView(act)
        tv.text = value
        tv.textSize = sizeSp
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (colorRes != null) tv.setTextColor(color(colorRes))
        return tv
    }

    private fun params(top: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        return lp
    }

    private fun box(bgRes: Int): LinearLayout {
        val b = LinearLayout(act)
        b.orientation = LinearLayout.VERTICAL
        // Each message/task sits in its own box with a visible line around it.
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(color(bgRes))
        bg.setStroke(dp(1), 0xFFB4B2A9.toInt())
        b.background = bg
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        return b
    }

    private fun button(textRes: Int, filled: Boolean, bgRes: Int = R.color.brand): MaterialButton {
        val b = MaterialButton(act)
        b.setText(textRes)
        if (filled) {
            b.backgroundTintList = ColorStateList.valueOf(color(bgRes))
        } else {
            b.backgroundTintList = ColorStateList.valueOf(color(R.color.off_bg))
            b.setTextColor(color(R.color.brand))
        }
        return b
    }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return ""
        val now = Calendar.getInstance()
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val f = if (sameDay) "HH:mm" else "dd MMM HH:mm"
        return SimpleDateFormat(f, Locale.getDefault()).format(Date(ms))
    }

    private fun find(id: Long): JSONObject? {
        val list = prefs.taskList()
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            if (t.optLong("id") == id) return t
        }
        return null
    }

    private fun linesDone(t: JSONObject): Pair<Int, Int> {
        val lines = t.optJSONArray("lines") ?: JSONArray()
        var done = 0
        for (i in 0 until lines.length()) if (lines.getJSONObject(i).optBoolean("done")) done++
        return done to lines.length()
    }

    // ------------------------------------------------------------------ list

    fun showList() {
        openId = 0L
        listSignature = ""
        renderList()
    }

    /** Rebuilds the list only when the data changed, so the screen doesn't flicker. */
    fun renderList() {
        val list = prefs.taskList()
        val signature = list.toString() + prefs.pendingTaskActions
        if (signature == listSignature) return
        listSignature = signature
        listBox.removeAllViews()
        val items = (0 until list.length()).map { list.getJSONObject(it) }
        val open = items.filter { it.optString("state") == "new" || it.optString("state") == "in_progress" }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("priority") }
                .thenBy { if (it.optLong("due_t") == 0L) Long.MAX_VALUE else it.optLong("due_t") })
        val done = items.filter { it.optString("state") == "done" }
        if (items.isEmpty()) {
            val empty = text(act.getString(R.string.inbox_empty), 14f, colorRes = R.color.off_text)
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(32), 0, dp(32))
            listBox.addView(empty, params(8))
            return
        }
        for (t in open) listBox.addView(card(t), params(8))
        if (done.isNotEmpty()) {
            listBox.addView(text("Done in the last 24 hours", 12f, bold = true, colorRes = R.color.off_text), params(16))
            for (t in done) listBox.addView(card(t), params(8))
        }
    }

    private fun card(t: JSONObject): View {
        val isMessage = t.optString("type") == "message"
        val state = t.optString("state")
        val now = System.currentTimeMillis()
        val overdue = !isMessage && state != "done" && t.optLong("due_t") in 1 until now
        val bg = when {
            state == "done" -> R.color.off_bg
            overdue -> R.color.warn_bg
            isMessage -> R.color.ok_bg
            else -> R.color.ok_bg
        }
        val c = box(bg)
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        val title = text(t.optString("name"), 15f, bold = state != "done")
        top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val tag = if (isMessage) "Message" else when (state) {
            "new" -> "Task · New"
            "in_progress" -> "Task · Started"
            else -> "Task"
        }
        top.addView(text(tag, 11f, colorRes = R.color.off_text))
        c.addView(top)
        val parts = mutableListOf<String>()
        if (isMessage) {
            parts.add(if (state == "done") "Read " + fmtTime(t.optLong("done_t")) else "Tap to read")
        } else {
            t.optString("customer").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            if (t.optLong("due_t") > 0) parts.add((if (overdue) "Overdue, was due " else "Due ") + fmtTime(t.optLong("due_t")))
            val (d, n) = linesDone(t)
            if (n > 0) parts.add("Checklist $d/$n")
            if (state == "done") parts.add("Done " + fmtTime(t.optLong("done_t")))
        }
        if (t.optInt("priority") >= 2 && state != "done") parts.add("Urgent")
        c.addView(text(parts.joinToString(" · "), 12f, colorRes = R.color.off_text), params(2))
        c.setOnClickListener { open(t.optLong("id")) }
        return c
    }

    // ------------------------------------------------------------------ detail

    private var detailSignature = ""

    fun open(id: Long) {
        openId = id
        renderDetail()
        act.onInboxNavigation()
    }

    /** After a sync, redraw the open item only if Odoo changed it (keeps what the rider is typing). */
    fun refreshDetailIfChanged() {
        if (openId == 0L) return
        val t = find(openId)
        val sig = t?.let { it.optString("state") + it.optString("name") + it.optString("description") +
            (it.optJSONArray("lines")?.length() ?: 0) } ?: "gone"
        if (sig != detailSignature) renderDetail()
    }

    fun back() {
        openId = 0L
        listSignature = ""
        act.onInboxNavigation()
    }

    fun renderDetail() {
        detailBox.removeAllViews()
        val t = find(openId)
        detailSignature = t?.let { it.optString("state") + it.optString("name") + it.optString("description") +
            (it.optJSONArray("lines")?.length() ?: 0) } ?: "gone"
        val backBtn = text(act.getString(R.string.back_to_inbox), 15f, bold = true, colorRes = R.color.brand)
        backBtn.setPadding(0, dp(8), dp(16), dp(8))
        backBtn.setOnClickListener { back() }
        detailBox.addView(backBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (t == null) {
            detailBox.addView(text("This item is no longer in your inbox.", 14f), params(8))
            return
        }
        val id = t.optLong("id")
        val isMessage = t.optString("type") == "message"
        val state = t.optString("state")
        detailBox.addView(text(if (isMessage) "Message" else "Task", 12f, bold = true, colorRes = R.color.brand), params(4))
        detailBox.addView(text(t.optString("name"), 20f, bold = true), params(2))
        val meta = mutableListOf<String>()
        if (t.optLong("sent_t") > 0) meta.add("Sent " + fmtTime(t.optLong("sent_t")))
        if (!isMessage && t.optLong("due_t") > 0) meta.add("Due " + fmtTime(t.optLong("due_t")))
        if (t.optInt("priority") >= 2) meta.add("Urgent") else if (t.optInt("priority") == 1) meta.add("High priority")
        if (meta.isNotEmpty()) detailBox.addView(text(meta.joinToString(" · "), 12f, colorRes = R.color.off_text), params(2))

        val desc = t.optString("description")
        if (desc.isNotEmpty()) {
            val d = box(R.color.off_bg)
            d.addView(text(desc, 15f))
            detailBox.addView(d, params(12))
        }

        if (isMessage) {
            if (state == "done") {
                detailBox.addView(text("Read " + fmtTime(t.optLong("done_t")) + " ✓", 14f, bold = true, colorRes = R.color.ok_text), params(20))
            } else {
                val ok = button(R.string.ok_read, true)
                ok.setOnClickListener {
                    prefs.addTaskAction(JSONObject().put("action", "read").put("id", id).put("t", System.currentTimeMillis()))
                    renderDetail()
                    act.syncNow()
                }
                detailBox.addView(ok, params(20))
            }
            return
        }

        // ---- task
        val customer = t.optString("customer")
        val address = t.optString("address")
        val phone = t.optString("phone")
        if (customer.isNotEmpty() || address.isNotEmpty()) {
            val cBox = box(R.color.off_bg)
            if (customer.isNotEmpty()) cBox.addView(text(customer, 15f, bold = true))
            if (address.isNotEmpty()) cBox.addView(text(address, 13f, colorRes = R.color.off_text), params(2))
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            val hasPlace = !t.isNull("lat") && !t.isNull("lon") || address.isNotEmpty()
            if (hasPlace) {
                val nav = button(R.string.navigate, false)
                nav.setOnClickListener { navigate(t) }
                row.addView(nav, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (phone.isNotEmpty()) {
                val call = button(R.string.call, false)
                call.setOnClickListener {
                    act.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", ""))))
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(8)
                row.addView(call, lp)
            }
            if (row.childCount > 0) cBox.addView(row, params(8))
            detailBox.addView(cBox, params(12))
        }

        val lines = t.optJSONArray("lines") ?: JSONArray()
        val done = state == "done"
        val ticks = checked.getOrPut(id) {
            val set = HashSet<Long>()
            for (i in 0 until lines.length()) if (lines.getJSONObject(i).optBoolean("done")) set.add(lines.getJSONObject(i).optLong("id"))
            set
        }
        val required = t.optBoolean("require_checklist", true) && lines.length() > 0
        var submit: MaterialButton? = null
        var hint: TextView? = null
        fun refreshSubmit() {
            val all = (0 until lines.length()).all { ticks.contains(lines.getJSONObject(it).optLong("id")) }
            submit?.isEnabled = !required || all
            hint?.visibility = if (required && !all) View.VISIBLE else View.GONE
        }
        if (lines.length() > 0) {
            detailBox.addView(text("Checklist", 14f, bold = true), params(16))
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                val cb = CheckBox(act)
                cb.text = line.optString("name")
                cb.textSize = 15f
                cb.isChecked = ticks.contains(line.optLong("id"))
                cb.isEnabled = !done
                cb.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) ticks.add(line.optLong("id")) else ticks.remove(line.optLong("id"))
                    refreshSubmit()
                }
                detailBox.addView(cb, params(2))
            }
        }

        if (done) {
            val note = t.optString("note")
            detailBox.addView(text("Submitted " + fmtTime(t.optLong("done_t")) + " ✓", 14f, bold = true, colorRes = R.color.ok_text), params(16))
            if (note.isNotEmpty()) detailBox.addView(text("Note: $note", 13f, colorRes = R.color.off_text), params(4))
            return
        }

        val noteInput = EditText(act)
        noteInput.hint = act.getString(R.string.note_hint)
        noteInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        noteInput.minLines = 2
        noteInput.setText(notes[id] ?: "")
        noteInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                notes[id] = s?.toString() ?: ""
            }
        })
        detailBox.addView(noteInput, params(12))

        if (state == "new") {
            val start = button(R.string.start_task, false)
            start.setOnClickListener {
                notes[id] = noteInput.text.toString()
                prefs.addTaskAction(JSONObject().put("action", "start").put("id", id).put("t", System.currentTimeMillis()))
                renderDetail()
                act.syncNow()
            }
            detailBox.addView(start, params(12))
        }

        submit = button(R.string.complete_task, true)
        submit.setOnClickListener { btn ->
            btn.isEnabled = false
            val note = noteInput.text.toString().trim()
            val time = System.currentTimeMillis()
            act.fetchLocation { loc ->
                val action = JSONObject().put("action", "complete").put("id", id).put("t", time).put("note", note)
                val arr = JSONArray()
                ticks.forEach { arr.put(it) }
                action.put("checked", arr)
                if (loc != null) {
                    action.put("lat", loc.latitude).put("lon", loc.longitude)
                        .put("acc", if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
                }
                prefs.addTaskAction(action)
                checked.remove(id)
                notes.remove(id)
                Toast.makeText(act, R.string.task_submitted, Toast.LENGTH_SHORT).show()
                renderDetail()
                act.syncNow()
            }
        }
        detailBox.addView(submit, params(if (state == "new") 8 else 12))
        hint = text(act.getString(R.string.tick_all), 12f, colorRes = R.color.warn_text)
        hint.gravity = Gravity.CENTER
        detailBox.addView(hint, params(6))
        refreshSubmit()
    }

    private fun navigate(t: JSONObject) {
        val dest = if (!t.isNull("lat") && !t.isNull("lon")) {
            "${t.optDouble("lat")},${t.optDouble("lon")}"
        } else {
            URLEncoder.encode(t.optString("address"), "UTF-8")
        }
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest")
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(act, "No maps app found.", Toast.LENGTH_SHORT).show()
        }
    }
}
