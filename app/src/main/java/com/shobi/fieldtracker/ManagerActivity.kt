package com.shobi.fieldtracker

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Manager screens: the manager page served by the Odoo module, shown full screen.
 * Opened instead of the rider screens when a Field Tracking manager logs in.
 */
class ManagerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val worker = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isManager) {
            backToLogin()
            return
        }
        val root = FrameLayout(this)
        web = WebView(this)
        progress = ProgressBar(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = android.view.Gravity.CENTER
        root.addView(progress, lp)
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "FieldTracker")
        // Show the page's alert/confirm boxes as normal Android dialogs (WebView blocks them otherwise).
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@ManagerActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@ManagerActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val server = Uri.parse(prefs.serverUrl)
                // Calls and Google Maps open in their own apps; Odoo pages stay here.
                if (url.scheme == "http" || url.scheme == "https") {
                    if (url.host == server.host && url.port == server.port) return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }
        }
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        worker.execute {
            // With several databases on the server, Odoo needs a fresh database session cookie.
            try {
                Api.refreshDatabaseSession(prefs)
            } catch (e: Exception) {
                // Offline or server down: the page shows its own error.
            }
            runOnUiThread {
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                if (prefs.sessionCookie.isNotEmpty()) {
                    cookies.setCookie(prefs.serverUrl, "session_id=" + prefs.sessionCookie + "; path=/")
                    cookies.flush()
                }
                web.loadUrl(prefs.serverUrl.trimEnd('/') + "/field_tracking/manager#token=" + Uri.encode(prefs.token))
            }
        }
    }

    @Deprecated("Uses the page's own back handling first")
    override fun onBackPressed() {
        web.evaluateJavascript("window.ftBack && window.ftBack()") { handled ->
            if (handled != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun backToLogin() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    inner class Bridge {
        @JavascriptInterface
        fun logout() {
            runOnUiThread {
                prefs.unpair()
                CookieManager.getInstance().removeAllCookies(null)
                backToLogin()
            }
        }
    }
}
