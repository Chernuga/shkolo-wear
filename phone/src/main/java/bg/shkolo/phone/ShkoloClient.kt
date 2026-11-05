package bg.shkolo.phone

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import bg.shkolo.common.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes the Shkolo session on the phone — no Worker, no Browser Run.
 *
 * Strategy, cheapest first:
 *   1. If we hold cookies and a plain GET works, answer with plain HTTP.
 *      (We proved cookie reuse works; this costs no browser time.)
 *   2. Otherwise drive a hidden WebView through Shkolo, letting the real
 *      fp.js build the fingerprint, then harvest the cookies.
 *
 * The WebView is the thing that replaces Cloudflare Browser Run: it is a real
 * Chromium renderer running locally, so fingerprinting and the challenge work
 * exactly as they do in a normal browser tab.
 */
class ShkoloClient(private val context: Context) {

    companion object {
        private const val TAG = "ShkoloClient"
        private const val BASE = "https://app.shkolo.bg"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var instance: ShkoloClient? = null

        fun get(context: Context): ShkoloClient =
            instance ?: synchronized(this) {
                instance ?: ShkoloClient(context.applicationContext).also { instance = it }
            }
    }

    data class Result(val status: Int, val body: String)

    private val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }

    private var webView: WebView? = null

    /** Cached CSRF token; the diary page rotates it, so keep it short-lived. */
    @Volatile
    private var csrf: String? = null

    @Volatile
    private var csrfAt: Long = 0

    @Volatile
    var pupilId: String? = null
        private set

    @Volatile
    var classYearId: String? = null
        private set

    val isLoggedIn: Boolean
        get() = cookieManager.getCookie(BASE)
            ?.contains("shkolo-cookie") == true

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView = webView ?: WebView(context).also { wv ->
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UA
            cacheMode = WebSettings.LOAD_DEFAULT

            // The fingerprint script needs canvas/audio/WebGL, so JS stays on.
            // Everything the login does NOT need is switched off: each of these
            // costs main-thread time in a hidden WebView and made the phone
            // feel frozen on slower devices.
            loadsImagesAutomatically = false
            blockNetworkImage = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }

        // A WebView with no window/layout params can throw when rendering;
        // keep it detached but sized, and never attach it to the view tree.
        wv.visibility = View.GONE
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        webView = wv
    }

    /**
     * Runs [script] once the page at [url] has loaded, returning the value.
     *
     * Guards against callbacks from *other* navigations: onPageFinished fires
     * for redirects and re-navigations too, which previously let a stale page
     * satisfy the gate before the one we wanted had finished.
     */
    private suspend fun <T> runInPage(
        url: String,
        timeoutMs: Long = 45_000,
        script: String,
        transform: (String) -> T?,
    ): T? = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val gate = CompletableDeferred<T?>()

        wv.stopLoading() // abandon anything still in flight

        wv.webViewClient = object : WebViewClient() {
            private var settled = false

            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (settled) return

                // Only accept the URL we actually asked for (ignoring trailing
                // slashes and query strings that Shkolo may append).
                val want = url.trimEnd('/')
                val got = (finishedUrl ?: "").substringBefore('?').trimEnd('/')
                if (!got.startsWith(want)) return

                settled = true
                view.evaluateJavascript(script) { raw ->
                    val value = raw
                        ?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() }
                        ?: raw
                    if (!gate.isCompleted) gate.complete(transform(value ?: ""))
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                // Only fail for the main frame, not stray subresources.
                if (request.isForMainFrame && !gate.isCompleted) gate.complete(null)
            }
        }

        wv.loadUrl(url)
        withTimeoutOrNull(timeoutMs) { gate.await() }
    }

    /**
     * Logs in with one page load.
     *
     * The script waits for fp.js to populate the hidden fingerprint inputs
     * (it runs asynchronously after load), then fills and submits the form.
     * We deliberately do NOT wait for "networkidle": Shkolo's analytics keep
     * the connection busy forever, which is what made login appear to freeze.
     */
    suspend fun login(username: String, password: String): Boolean {
        val user = jsString(username)
        val pass = jsString(password)

        val script = """
            (function(){
              try {
                var u = document.querySelector('input[name=login]');
                var p = document.querySelector('input[name=password]');
                if (!u || !p) return 'noform';

                var fp = document.querySelector('input[name="fingerprint[visitor_id]"]');
                if (!fp || !fp.value) return 'nofp';   // fp.js not ready yet

                u.value = $user;
                p.value = $pass;
                var form = u.closest('form');
                if (!form) return 'noform';

                // Submit on the next tick so the caller can return first.
                setTimeout(function(){ form.submit(); }, 50);
                return 'submitted';
              } catch (e) { return 'error:' + e.message; }
            })()
        """.trimIndent()

        // fp.js is async and can be slow on first run; retry a few times
        // instead of failing the whole login on a cold cache.
        var submitted: String? = null
        for (attempt in 1..3) {
            if (attempt > 1) {
                Log.d(TAG, "retrying login submit (attempt $attempt), last=$submitted")
                delay(1_500)
                // Reload so the form and fp.js run again.
                runInPage(url = "$BASE/", timeoutMs = 30_000, script = "1") { it }
            }

            submitted = runInPage(
                url = "$BASE/",
                timeoutMs = 60_000,
                script = script,
            ) { it }

            if (submitted == "submitted") break
            if (submitted != null && submitted != "nofp") break // noform/error: stop early
        }

        Log.d(TAG, "login submit -> $submitted")
        if (submitted != "submitted") return false

        return waitForSession()
    }

    /**
     * Polls until Shkolo's session cookie exists (the reliable success signal),
     * rather than trusting a navigation that may never "finish".
     */
    private suspend fun waitForSession(timeoutMs: Long = 45_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(700)
            if (isLoggedIn) {
                cookieManager.flush()
                capturePupilIds()
                return true
            }
        }
        return false
    }

    /** Reads pupil/class ids from whatever page the WebView ended up on. */
    private suspend fun capturePupilIds() {
        runInPage(
            url = "$BASE/dashboard",
            timeoutMs = 20_000,
            script = """
                (function(){
                  var p = document.querySelector('#pupil_id_field');
                  var c = document.querySelector('#class_year_id_field');
                  return JSON.stringify({
                    pupil: p ? p.value : null,
                    cls: c ? c.value : null
                  });
                })()
            """.trimIndent(),
        ) {
            runCatching {
                val o = org.json.JSONObject(it)
                pupilId = o.optString("pupil", "").ifBlank { null }
                classYearId = o.optString("cls", "").ifBlank { null }
            }
            true
        }
    }

    /** Single-quoted JS literal, with everything hostile escaped. */
    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "").replace("</", "<\\/") + "'"

    // ---------------------------------------------------------------- requests

    /**
     * Fetch a Shkolo path on behalf of the watch.
     * Tries plain HTTP first (cheap); falls back to the WebView.
     */
    suspend fun get(path: String, query: String = ""): Result {
        if (!isLoggedIn) {
            return Result(Protocol.State.NOT_LOGGED_IN, """{"error":"Not logged in"}""")
        }

        val url = buildString {
            append(BASE).append(path)
            if (query.isNotBlank()) append('?').append(query)
        }

        // Cheap path: reuse harvested cookies over plain HTTP.
        val plain = withContext(Dispatchers.IO) { tryPlainGet(url) }
        if (plain != null && plain.status == 200 && !looksLikeLoginWall(plain.body)) {
            return plain
        }

        // Expensive path: let the real browser do it.
        val viaWebView = viaWebView(url)
        return viaWebView ?: Result(
            Protocol.State.FAILED,
            """{"error":"Shkolo request failed"}""",
        )
    }

    private fun tryPlainGet(url: String): Result? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty(
            "Cookie",
            cookieManager.getCookie(BASE) ?: "",
        )
        if (url.contains("/ajax/")) {
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        Result(code, body)
    } catch (t: Throwable) {
        Log.w(TAG, "plain GET failed: ${t.message}")
        null
    }

    /**
     * Runs the GET inside the WebView and returns the body. Used only when the
     * cheap path was rejected (Cloudflare / fingerprint).
     */
    private suspend fun viaWebView(url: String): Result? = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val done = CompletableDeferred<String?>()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                view.evaluateJavascript(
                    "document.documentElement.outerHTML",
                ) { html ->
                    done.complete(unescapeJsString(html))
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = null
        }

        wv.loadUrl(url)
        val body = withTimeoutOrNull(45_000) { done.await() }
        if (body == null) return@withContext null
        Result(200, body)
    }

    // ---------------------------------------------------------------- helpers

    private fun looksLikeLoginWall(html: String): Boolean =
        html.contains("name=\"password\"") || html.contains("Page Expired")

    /**
     * WebView.evaluateJavascript hands back a JSON-encoded string; unwrap the
     * escapes instead of shipping a full JSON parser into the page.
     */
    private fun unescapeJsString(raw: String?): String {
        if (raw == null) return ""
        if (raw.length < 2) return raw
        if (!raw.startsWith("\"")) return raw
        return runCatching { org.json.JSONTokener(raw).nextValue() as String }.getOrDefault(raw)
    }

    /** Best-effort CSRF token for /ajax/ calls made from Java. */
    suspend fun freshCsrf(): String? {
        val cached = csrf
        if (cached != null && System.currentTimeMillis() - csrfAt < 10 * 60_000) return cached

        val token = withContext(Dispatchers.IO) {
            val r = tryPlainGet("$BASE/diary") ?: return@withContext null
            Regex("<meta name=\"csrf-token\" content=\"([^\"]+)\"")
                .find(r.body)?.groupValues?.get(1)
        }

        csrf = token
        csrfAt = System.currentTimeMillis()
        return token
    }

    fun logout() {
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        csrf = null
        pupilId = null
        classYearId = null
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }
}
