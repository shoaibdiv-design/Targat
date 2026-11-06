package com.shobi.fieldtracker

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor

/**
 * RSM screens (read only): Customers (receivable with ageing) and Invoices (Draft, Not paid,
 * Partially paid, Paid). Everything is loaded from Odoo; the server only returns this RSM's data.
 */
class RsmUi(
    private val act: MainActivity,
    private val prefs: Prefs,
    private val box: LinearLayout,
    private val worker: Executor,
    private val post: (Runnable) -> Unit,
) {
    var mode = "customers"          // customers | invoices
        private set
    private var screen = "list"     // list | customer | invoice
    private var customerId = 0
    private var invoiceId = 0
    private var cameFromCustomer = false
    private var invoiceFrom = "list"        // list | customer | ledger
    private var customerName = ""
    private var ledgerPeriod = "3m"         // month | 3m | year | custom
    private var ledgerFrom = ""
    private var ledgerTo = ""
    private var search = ""
    private var invFilter = "all"
    private var custFilter = "all"
    private var dtype = 0           // one Distribution Type chosen with the chips (0 = all allowed)
    private var loading = false
    private var currency = "Rs"

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private val money: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private fun rs(v: Double) = currency + " " + money.format(Math.round(v))
    private fun num(v: Double) = money.format(Math.round(v))

    private fun text(v: CharSequence, sp: Float = 14f, bold: Boolean = false, colorRes: Int? = null): TextView {
        val tv = TextView(act)
        tv.text = v
        tv.textSize = sp
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (colorRes != null) tv.setTextColor(color(colorRes))
        return tv
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(top)
        return p
    }

    private fun rounded(bg: Int, radius: Int = 12): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(radius).toFloat()
        d.setColor(bg)
        return d
    }

    private fun card(bgRes: Int): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(color(bgRes))
        c.setPadding(dp(12), dp(10), dp(12), dp(10))
        return c
    }

    private fun pill(status: String, residual: Double): TextView {
        val (label, bg, fg) = when (status) {
            "paid" -> Triple("Paid", 0xFFE1F5EE.toInt(), 0xFF085041.toInt())
            "partial" -> Triple("Partially paid · due " + num(residual), 0xFFFAEEDA.toInt(), 0xFF633806.toInt())
            "draft" -> Triple("Draft", 0xFFF1EFE8.toInt(), 0xFF444441.toInt())
            else -> Triple("Not Paid", 0xFFE24B4A.toInt(), 0xFFFFFFFF.toInt())
        }
        val t = text(label, 11f, bold = true)
        t.setTextColor(fg)
        t.background = rounded(bg, 10)
        t.setPadding(dp(8), dp(2), dp(8), dp(2))
        return t
    }

    private fun dateText(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
            SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d!!)
        } catch (e: Exception) { iso }
    }

    // ------------------------------------------------------------------ navigation
    fun open(newMode: String) {
        if (mode != newMode || screen != "list") {
            mode = newMode
            screen = "list"
            search = ""
        }
        load()
    }

    /** Android back: returns true when handled inside these screens. */
    fun back(): Boolean {
        when (screen) {
            "invoice" -> {
                screen = invoiceFrom
                load(); return true
            }
            "ledger" -> { screen = "customer"; load(); return true }
            "customer" -> { screen = "list"; load(); return true }
        }
        return false
    }

    private fun load() {
        if (loading) return
        loading = true
        box.removeAllViews()
        box.addView(text("Loading…", 14f, colorRes = R.color.off_text), lp(16))
        val (path, body) = when {
            screen == "invoice" -> "invoice" to JSONObject().put("id", invoiceId)
            screen == "ledger" -> "ledger" to JSONObject().put("id", customerId).put("period", ledgerPeriod)
                .put("date_from", ledgerFrom).put("date_to", ledgerTo)
            screen == "customer" -> "customer" to JSONObject().put("id", customerId)
            mode == "invoices" -> "invoices" to JSONObject().put("filter", invFilter).put("search", search).put("dtype", dtype)
            else -> "customers" to JSONObject().put("search", search).put("dtype", dtype)
        }
        worker.execute {
            var data: JSONObject? = null
            var error: String? = null
            try {
                data = Api.post(prefs, "/field_tracking/api/rsm/$path", body).optJSONObject("data")
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "No connection. Check the internet and try again."
            }
            post(Runnable {
                loading = false
                box.removeAllViews()
                if (data == null) showError(error ?: "Something went wrong.")
                else when (path) {
                    "customers" -> renderCustomers(data)
                    "customer" -> renderCustomer(data)
                    "invoices" -> renderInvoices(data)
                    "ledger" -> renderLedger(data)
                    else -> renderInvoice(data)
                }
            })
        }
    }

    private fun showError(msg: String) {
        val c = card(R.color.warn_bg)
        c.addView(text(msg, 14f, colorRes = R.color.warn_text))
        box.addView(c, lp(12))
        val retry = text("Try again", 15f, bold = true, colorRes = R.color.brand)
        retry.setPadding(0, dp(12), 0, dp(12))
        retry.setOnClickListener { load() }
        box.addView(retry, lp(4))
    }

    private fun backLink(label: String) {
        val b = text("‹ $label", 15f, bold = true, colorRes = R.color.brand)
        b.setPadding(0, dp(10), 0, dp(6))
        b.setOnClickListener { back() }
        box.addView(b, lp(4))
    }

    private fun searchBox(hint: String) {
        val e = EditText(act)
        e.hint = hint
        e.setText(search)
        e.inputType = InputType.TYPE_CLASS_TEXT
        e.imeOptions = EditorInfo.IME_ACTION_SEARCH
        e.setSingleLine()
        e.setOnEditorActionListener { v, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                search = v.text.toString().trim()
                load(); true
            } else false
        }
        box.addView(e, lp(8))
    }

    private fun chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        val scroll = HorizontalScrollView(act)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        for ((key, label) in options) {
            val on = key == selected
            val c = text(label, 13f, bold = on)
            c.setTextColor(color(if (on) R.color.ok_text else R.color.off_text))
            val bg = rounded(color(if (on) R.color.ok_bg else android.R.color.white), 16)
            bg.setStroke(dp(1), color(if (on) R.color.brand else R.color.off_bg))
            c.background = bg
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.rightMargin = dp(6)
            c.setOnClickListener { onPick(key) }
            row.addView(c, p)
        }
        scroll.addView(row)
        box.addView(scroll, lp(10))
    }

    /** Distribution Type chips: only the types the administrator allowed for this login. */
    private fun typeChips(d: JSONObject) {
        val types = d.optJSONArray("types") ?: return
        if (types.length() < 2) return
        val opts = mutableListOf("0" to "All my types")
        for (i in 0 until types.length()) {
            val t = types.getJSONObject(i)
            opts.add(t.optInt("id").toString() to t.optString("name"))
        }
        chips(opts, dtype.toString()) { dtype = it.toInt(); load() }
    }

    private fun ageing(target: LinearLayout, d: JSONObject) {
        val grid = LinearLayout(act)
        grid.orientation = LinearLayout.HORIZONTAL
        val buckets = listOf("b0" to "0–30", "b30" to "31–60", "b60" to "61–90", "b90" to "91–120", "b120" to "120+")
        val bgs = listOf(0xFFE1F5EE.toInt(), 0xFFE6F1FB.toInt(), 0xFFFAEEDA.toInt(), 0xFFFCEBEB.toInt(), 0xFFF7C1C1.toInt())
        buckets.forEachIndexed { i, (k, label) ->
            val cell = LinearLayout(act)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.background = rounded(bgs[i], 6)
            cell.setPadding(dp(2), dp(5), dp(2), dp(5))
            cell.addView(text(label, 10f, colorRes = R.color.off_text).also { it.gravity = Gravity.CENTER })
            cell.addView(text(short(d.optDouble(k, 0.0)), 12f, bold = true).also { it.gravity = Gravity.CENTER })
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) p.leftMargin = dp(3)
            grid.addView(cell, p)
        }
        target.addView(grid, lp(8))
    }

    private fun short(v: Double): String = when {
        Math.abs(v) >= 1_000_000 -> String.format(Locale.US, "%.1f M", v / 1_000_000)
        Math.abs(v) >= 1_000 -> String.format(Locale.US, "%.0f K", v / 1_000)
        else -> money.format(Math.round(v))
    }

    /** A bordered box that holds transaction rows (one line between every row). */
    private fun lined(): LinearLayout {
        val last = if (box.childCount > 0) box.getChildAt(box.childCount - 1) else null
        if (last is LinearLayout && last.tag == "lined") return last
        val l = LinearLayout(act)
        l.orientation = LinearLayout.VERTICAL
        l.tag = "lined"
        val bg = rounded(0xFFFFFFFF.toInt(), 8)
        bg.setStroke(dp(1), 0xFFB4B2A9.toInt())
        l.background = bg
        l.setPadding(dp(1), dp(1), dp(1), dp(1))
        l.clipToOutline = true
        box.addView(l, lp(8))
        return l
    }

    /** Ends the current bordered box so the next rows start a new one. */
    private fun endLined() {
        val v = View(act)
        box.addView(v, LinearLayout.LayoutParams(1, 0))
    }

    private fun addLinedRow(target: LinearLayout, r: View) {
        val idx = target.childCount
        if (idx > 0) {
            val line = View(act)
            line.setBackgroundColor(0xFFB4B2A9.toInt())
            target.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val rowNo = (idx + 1) / 2
        if (rowNo % 2 == 1) r.setBackgroundColor(0xFFF4F3EE.toInt())
        target.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun row(left1: String, left2: String, right1: String, right2: View?, onClick: () -> Unit) {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(10), dp(9), dp(10), dp(9))
        val l = LinearLayout(act)
        l.orientation = LinearLayout.VERTICAL
        l.addView(text(left1, 15f, bold = true))
        if (left2.isNotEmpty()) l.addView(text(left2, 12f, colorRes = R.color.off_text))
        r.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rt = LinearLayout(act)
        rt.orientation = LinearLayout.VERTICAL
        rt.gravity = Gravity.END
        rt.addView(text(right1, 15f, bold = true).also { it.gravity = Gravity.END })
        if (right2 != null) rt.addView(right2)
        r.addView(rt)
        r.setOnClickListener { onClick() }
        addLinedRow(lined(), r)
    }

    /** One row of a ruled table: cells with weights, optional bold/colour. */
    private fun tableRow(target: LinearLayout, cells: List<Triple<String, Float, Int>>, bold: Boolean = false,
                         sub: List<String>? = null, colors: List<Int?>? = null, onClick: (() -> Unit)? = null) {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(dp(4), dp(6), dp(4), dp(6))
        cells.forEachIndexed { i, (value, weight, gravity) ->
            val cell = LinearLayout(act)
            cell.orientation = LinearLayout.VERTICAL
            val t = text(value, 12f, bold = bold || (i == 1 && sub != null))
            t.gravity = gravity
            colors?.getOrNull(i)?.let { t.setTextColor(it) }
            cell.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val sv = sub?.getOrNull(i)
            if (!sv.isNullOrEmpty()) cell.addView(text(sv, 10f, colorRes = R.color.off_text).also { it.gravity = gravity })
            val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            p.leftMargin = dp(2); p.rightMargin = dp(2)
            r.addView(cell, p)
        }
        if (onClick != null) r.setOnClickListener { onClick() }
        addLinedRow(target, r)
    }

    // ------------------------------------------------------------------ customers
    private fun renderCustomers(d: JSONObject) {
        currency = d.optString("currency", "Rs")
        val sum = card(R.color.ok_bg)
        typeChips(d)
        sum.addView(text("Total receivable (partner ledger) · " + d.optInt("count") + " customers", 12f, colorRes = R.color.ok_text))
        sum.addView(text(rs(d.optDouble("balance")), 22f, bold = true, colorRes = R.color.ok_text))
        val extra = mutableListOf<String>()
        if (d.optDouble("draft") != 0.0) extra.add("+ draft " + rs(d.optDouble("draft")) + " not posted = " + rs(d.optDouble("with_draft")))
        if (d.optDouble("overdue") != 0.0) extra.add("overdue " + rs(d.optDouble("overdue")))
        if (extra.isNotEmpty()) sum.addView(text(extra.joinToString(" · "), 12f, colorRes = R.color.ok_text))
        box.addView(sum, lp(12))
        ageing(box, d)
        box.addView(text(if (d.optString("ageing") == "invoice") "Ageing: days since invoice date" else "Ageing: days past due date",
            11f, colorRes = R.color.off_text), lp(4))
        searchBox("Search name or customer code")
        chips(listOf("all" to "All", "due" to "With balance", "overdue" to "Overdue", "clear" to "Clear"), custFilter) {
            custFilter = it; renderCustomers(d.also { box.removeAllViews() })
        }
        val list = d.optJSONArray("customers")
        var shown = 0
        for (i in 0 until (list?.length() ?: 0)) {
            val c = list!!.getJSONObject(i)
            val bal = c.optDouble("balance")
            val ok = when (custFilter) {
                "due" -> bal > 0.5
                "overdue" -> c.optDouble("overdue") > 0.5
                "clear" -> Math.abs(bal) <= 0.5
                else -> true
            }
            if (!ok) continue
            shown++
            val meta = listOf(c.optString("code"), c.optString("zone"), c.optString("dist_type")).filter { it.isNotBlank() }
            val note = when {
                c.optDouble("overdue") > 0.5 -> text("overdue " + num(c.optDouble("overdue")), 12f, colorRes = R.color.danger)
                c.optDouble("draft") != 0.0 -> text("+ draft " + num(c.optDouble("draft")), 12f, colorRes = R.color.off_text)
                Math.abs(bal) <= 0.5 -> text("clear", 12f, colorRes = R.color.off_text)
                else -> null
            }
            row(c.optString("name"), meta.joinToString(" · "), num(bal), note) {
                customerId = c.optInt("id"); screen = "customer"; load()
            }
        }
        if (shown == 0) box.addView(text("No customers here.", 14f, colorRes = R.color.off_text), lp(12))
    }

    private fun renderCustomer(d: JSONObject) {
        backLink("Customers")
        box.addView(text(d.optString("name"), 19f, bold = true), lp(2))
        val meta = listOf(d.optString("code"), d.optString("zone"), d.optString("dist_type"),
            if (d.optString("booker").isNotEmpty()) "Booker: " + d.optString("booker") else "").filter { it.isNotBlank() }
        if (meta.isNotEmpty()) box.addView(text(meta.joinToString(" · "), 12f, colorRes = R.color.off_text), lp(2))
        val phone = d.optString("phone")
        if (phone.isNotEmpty()) {
            val call = text("📞 $phone", 14f, bold = true, colorRes = R.color.brand)
            call.setPadding(0, dp(6), 0, dp(2))
            call.setOnClickListener { act.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            box.addView(call, lp(2))
        }
        val c = card(R.color.off_bg)
        c.addView(text("Partner ledger balance", 12f, colorRes = R.color.off_text))
        c.addView(text(rs(d.optDouble("balance")), 22f, bold = true))
        ageing(c, d)
        val notes = mutableListOf("Overdue invoices: " + rs(d.optDouble("overdue")))
        if (d.optDouble("unapplied") != 0.0) notes.add("Payments/credits not matched to an invoice: " + rs(d.optDouble("unapplied")))
        if (d.optDouble("draft") != 0.0) notes.add("Draft (not posted): " + rs(d.optDouble("draft")) + " → with draft " + rs(d.optDouble("with_draft")))
        c.addView(text(notes.joinToString("\n"), 12f, colorRes = R.color.off_text), lp(6))
        box.addView(c, lp(10))
        customerName = d.optString("name")
        val ledgerBtn = text("📒  Partner ledger", 15f, bold = true, colorRes = R.color.brand)
        ledgerBtn.gravity = Gravity.CENTER
        val lb = rounded(0xFFFFFFFF.toInt(), 22)
        lb.setStroke(dp(2), color(R.color.brand))
        ledgerBtn.background = lb
        ledgerBtn.setPadding(0, dp(10), 0, dp(10))
        ledgerBtn.setOnClickListener {
            ledgerPeriod = if (prefs.rsmCutoff.isNotEmpty()) "cutoff" else "3m"
            screen = "ledger"; load()
        }
        box.addView(ledgerBtn, lp(10))
        box.addView(text("Invoices", 13f, bold = true, colorRes = R.color.off_text), lp(14))
        invoiceRows(d.optJSONArray("invoices"), fromCustomer = true, showCustomer = false)
    }

    // ------------------------------------------------------------------ invoices
    private fun renderInvoices(d: JSONObject) {
        typeChips(d)
        val counts = d.optJSONObject("counts") ?: JSONObject()
        fun lbl(k: String, name: String) = if (counts.has(k)) "$name ${counts.optInt(k)}" else name
        chips(listOf("all" to "All", "draft" to lbl("draft", "Draft"), "unpaid" to lbl("unpaid", "Not paid"),
            "partial" to lbl("partial", "Partial"), "paid" to lbl("paid", "Paid"), "overdue" to lbl("overdue", "Overdue"),
            "month" to "This month"), invFilter) { invFilter = it; load() }
        searchBox("Invoice no., SA code or customer")
        val c = card(R.color.off_bg)
        c.addView(text("Due on shown invoices: " + rs(d.optDouble("total_due")), 13f, bold = true))
        box.addView(c, lp(8))
        invoiceRows(d.optJSONArray("invoices"), fromCustomer = false, showCustomer = true)
        box.addView(text("All unpaid and draft invoices, and paid ones from the last 3 months.", 11f,
            colorRes = R.color.off_text).also { it.gravity = Gravity.CENTER }, lp(10))
    }

    private fun invoiceRows(list: org.json.JSONArray?, fromCustomer: Boolean, showCustomer: Boolean) {
        val n = list?.length() ?: 0
        if (n == 0) {
            box.addView(text("No invoices.", 14f, colorRes = R.color.off_text), lp(10)); return
        }
        for (i in 0 until n) {
            val inv = list!!.getJSONObject(i)
            val sub = mutableListOf<String>()
            if (showCustomer) sub.add(inv.optString("customer"))
            if (inv.optString("dist_type").isNotEmpty()) sub.add(inv.optString("dist_type"))
            if (inv.optString("sa_code").isNotEmpty()) sub.add("SA " + inv.optString("sa_code"))
            if (inv.optString("delivery").isNotEmpty()) sub.add("Delivery " + dateText(inv.optString("delivery")))
            else sub.add(dateText(inv.optString("date")))
            if (inv.optString("due").isNotEmpty() && inv.optString("status") != "paid")
                sub.add((if (inv.optBoolean("overdue")) "overdue since " else "due ") + dateText(inv.optString("due")))
            val p = pill(inv.optString("status"), inv.optDouble("residual"))
            val name = inv.optString("name") + if (inv.optString("type") == "refund") " (credit note)" else ""
            row(name, sub.filter { it.isNotBlank() }.joinToString(" · "), num(inv.optDouble("total")), p) {
                invoiceId = inv.optInt("id"); cameFromCustomer = fromCustomer
                invoiceFrom = if (fromCustomer) "customer" else "list"; screen = "invoice"; load()
            }
        }
    }

    private fun renderInvoice(d: JSONObject) {
        backLink(when (invoiceFrom) { "ledger" -> "Partner ledger"; "customer" -> "Customer"; else -> "Invoices" })
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(text(d.optString("name"), 19f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pill(d.optString("status"), d.optDouble("residual")))
        box.addView(head, lp(2))
        box.addView(text(d.optString("customer"), 14f, colorRes = R.color.off_text), lp(2))
        // Ruled table: one line per detail. Delivery date first, no products.
        val t = lined()
        val danger = color(R.color.danger)
        fun kv(k: String, v: String, bold: Boolean = false, c: Int? = null) {
            tableRow(t, listOf(Triple(k, 1f, Gravity.START), Triple(v, 1f, Gravity.END)), bold = bold,
                colors = listOf(null, c))
        }
        tableRow(t, listOf(Triple("Detail", 1f, Gravity.START), Triple("Value", 1f, Gravity.END)),
            colors = listOf(color(R.color.off_text), color(R.color.off_text)))
        kv("Delivery date", if (d.optString("delivery").isNotEmpty()) dateText(d.optString("delivery")) else "—", bold = true)
        kv("Invoice date", dateText(d.optString("date")))
        if (d.optString("due").isNotEmpty()) kv("Due date", dateText(d.optString("due")),
            c = if (d.optBoolean("overdue")) danger else null)
        val details = d.optJSONArray("details")
        for (i in 0 until (details?.length() ?: 0)) {
            val x = details!!.getJSONObject(i)
            val v = x.opt("value")
            kv(x.optString("label"), if (v is Number) num(v.toDouble()) else v.toString())
        }
        kv("Tax excluded", num(d.optDouble("untaxed")))
        kv("Tax", num(d.optDouble("tax")))
        kv("Total", rs(d.optDouble("total")), bold = true)
        if (d.optString("status") != "paid") kv("Amount due", rs(d.optDouble("residual")), bold = true, c = danger)
        endLined()
        box.addView(text("Show only: nothing can be changed from the app.", 11f, colorRes = R.color.off_text)
            .also { it.gravity = Gravity.CENTER }, lp(10))
    }

    // ------------------------------------------------------------------ partner ledger
    private fun pickDate(current: String, minDate: String = "", onPick: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        if (current.length == 10) {
            try {
                cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(current)!!
            } catch (e: Exception) { }
        }
        val dlg = android.app.DatePickerDialog(act, { _, y, m, day ->
            onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day))
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
        if (minDate.length == 10) {
            try {
                dlg.datePicker.minDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(minDate)!!.time
            } catch (e: Exception) { }
        }
        dlg.show()
    }

    private fun renderLedger(d: JSONObject) {
        currency = d.optString("currency", currency)
        backLink(d.optString("customer").ifEmpty { customerName })
        box.addView(text("Partner ledger", 19f, bold = true), lp(2))
        val cutoff = d.optString("cutoff")
        val periods = mutableListOf<Pair<String, String>>()
        if (cutoff.isNotEmpty()) periods.add("cutoff" to "From " + dateText(cutoff))
        periods.addAll(listOf("month" to "This month", "3m" to "Last 3 months", "year" to "This year", "custom" to "Custom"))
        chips(periods, d.optString("period", ledgerPeriod)) { p ->
            if (p == "custom") {
                pickDate(d.optString("date_from"), cutoff) { from ->
                    pickDate(d.optString("date_to"), from) { to ->
                        ledgerPeriod = "custom"; ledgerFrom = from; ledgerTo = to; load()
                    }
                }
            } else {
                ledgerPeriod = p; load()
            }
        }
        // Export: the same Partner Ledger print as Odoo (Dynamic Accounting Reports), for this period.
        val exp = LinearLayout(act)
        exp.orientation = LinearLayout.HORIZONTAL
        fun expBtn(label: String, fmt: String, bg: Int, fg: Int, stroke: Int): TextView {
            val b = text(label, 14f, bold = true)
            b.setTextColor(fg)
            b.gravity = Gravity.CENTER
            val dr = rounded(bg, 20)
            dr.setStroke(dp(1), stroke)
            b.background = dr
            b.setPadding(0, dp(9), 0, dp(9))
            b.setOnClickListener { exportLedger(fmt, d) }
            return b
        }
        val pdfBtn = expBtn("⬇  PDF", "pdf", 0xFFFCEBEB.toInt(), 0xFF791F1F.toInt(), 0xFFF09595.toInt())
        val xlsBtn = expBtn("⬇  Excel", "xlsx", 0xFFE1F5EE.toInt(), 0xFF085041.toInt(), 0xFF5DCAA5.toInt())
        val p1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); p1.rightMargin = dp(4)
        val p2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); p2.leftMargin = dp(4)
        exp.addView(pdfBtn, p1)
        exp.addView(xlsBtn, p2)
        box.addView(exp, lp(8))
        box.addView(text(dateText(d.optString("date_from")) + " – " + dateText(d.optString("date_to")) +
            " · posted entries only" + (if (cutoff.isNotEmpty()) " · cut-off " + dateText(cutoff) +
            ", earlier dates can't be chosen" else ""), 12f, colorRes = R.color.off_text), lp(6))

        val t = lined()
        val head = color(R.color.off_text)
        val green = color(R.color.brand)
        val w = listOf(0.9f, 2.1f, 1.25f, 1.25f, 1.35f)
        tableRow(t, listOf(Triple("Date", w[0], Gravity.START), Triple("Transaction", w[1], Gravity.START),
            Triple("Debit", w[2], Gravity.END), Triple("Credit", w[3], Gravity.END), Triple("Balance", w[4], Gravity.END)),
            colors = listOf(head, head, head, head, head))
        tableRow(t, listOf(Triple("", w[0], Gravity.START), Triple("Opening balance as on " + dateText(d.optString("date_from")), w[1], Gravity.START),
            Triple("", w[2], Gravity.END), Triple("", w[3], Gravity.END), Triple(num(d.optDouble("opening")), w[4], Gravity.END)),
            bold = true)
        val lines = d.optJSONArray("lines")
        val n = lines?.length() ?: 0
        for (i in 0 until n) {
            val l = lines!!.getJSONObject(i)
            val dr = l.optDouble("debit")
            val cr = l.optDouble("credit")
            val date = dateText(l.optString("date")).take(5)
            val sub = listOf(l.optString("kind"), l.optString("sub")).filter { it.isNotBlank() }.joinToString(" · ")
            val invId = l.optInt("invoice_id")
            tableRow(t, listOf(Triple(date, w[0], Gravity.START), Triple(l.optString("name"), w[1], Gravity.START),
                Triple(if (dr != 0.0) num(dr) else "", w[2], Gravity.END), Triple(if (cr != 0.0) num(cr) else "", w[3], Gravity.END),
                Triple(num(l.optDouble("balance")), w[4], Gravity.END)),
                sub = listOf("", sub, "", "", ""), colors = listOf(null, if (invId > 0) green else null, null, green, null),
                onClick = if (invId > 0) ({ invoiceId = invId; invoiceFrom = "ledger"; screen = "invoice"; load() }) else null)
        }
        if (n == 0) tableRow(t, listOf(Triple("No entries in this period.", 1f, Gravity.CENTER)))
        tableRow(t, listOf(Triple("", w[0], Gravity.START), Triple("Totals / closing", w[1], Gravity.START),
            Triple(num(d.optDouble("total_debit")), w[2], Gravity.END), Triple(num(d.optDouble("total_credit")), w[3], Gravity.END),
            Triple(num(d.optDouble("closing")), w[4], Gravity.END)), bold = true)
        endLined()
        if (d.optBoolean("truncated")) box.addView(text("Showing the first 1,000 entries; choose a shorter period.", 11f,
            colorRes = R.color.warn_text), lp(6))
        box.addView(text("Closing balance = Odoo's Partner Ledger for this customer. Tap a green invoice to open it.",
            11f, colorRes = R.color.off_text).also { it.gravity = Gravity.CENTER }, lp(10))
    }

    private var exporting = false

    /** Asks the server for the Partner Ledger PDF/Excel and opens the phone's share sheet. */
    private fun exportLedger(fmt: String, d: JSONObject) {
        if (exporting) return
        exporting = true
        android.widget.Toast.makeText(act, if (fmt == "pdf") "Making the PDF…" else "Making the Excel file…",
            android.widget.Toast.LENGTH_SHORT).show()
        val body = JSONObject().put("id", customerId).put("format", fmt)
            .put("period", "custom").put("date_from", d.optString("date_from")).put("date_to", d.optString("date_to"))
        worker.execute {
            var file: java.io.File? = null
            var error: String? = null
            try {
                val (name, bytes) = Api.download(prefs, "/field_tracking/api/rsm_export", body)
                val dir = java.io.File(act.cacheDir, "exports")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }   // keep only the latest export
                file = java.io.File(dir, name)
                file.writeBytes(bytes)
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "No connection. Check the internet and try again."
            }
            post(Runnable {
                exporting = false
                val f = file
                if (f == null) {
                    android.widget.Toast.makeText(act, error ?: "Could not make the file.", android.widget.Toast.LENGTH_LONG).show()
                    return@Runnable
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".files", f)
                    val send = Intent(Intent.ACTION_SEND)
                    send.type = if (fmt == "pdf") "application/pdf"
                        else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    send.putExtra(Intent.EXTRA_STREAM, uri)
                    send.putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('_', ' '))
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    act.startActivity(Intent.createChooser(send, "Share partner ledger"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(act, "No app found to open or share the file.", android.widget.Toast.LENGTH_LONG).show()
                }
            })
        }
    }
}
