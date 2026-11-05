package bg.shkolo.phone

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import bg.shkolo.common.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
            // The fingerprint script must be allowed to run normally.
            blockNetworkImage = false
        }
        wv.webViewClient = WebViewClient()
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        webView = wv
    }

    /**
     * Runs [script] inside a page we first navigate to (so same-origin XHR and
     * CSRF behave normally), returning the evaluated value.
     */
    private suspend fun <T> runInPage(
        url: String,
        timeoutMs: Long = 45_000,
        script: String,
        transform: (String) -> T?,
    ): T? = withContext(Dispatchers.Main) {
        val wv = ensureWebView()
        val gate = CompletableDeferred<T?>()

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                view.evaluateJavascript(script) { raw ->
                    val value = raw
                        ?.let { runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull() }
                        ?: raw
                    gate.complete(transform(value ?: ""))
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = null
        }

        wv.loadUrl(url)
        withTimeoutOrNull(timeoutMs) { gate.await() }
    }

    /** Reads the fingerprint values fp.js put into the hidden inputs. */
    private val READ_FINGERPRINT = """
        (function(){
          var f = {};
          ['visitor_id','user_agent','timezone','webgl_vendor','fonts','canvas_hash','audio_hash','browser_hash']
            .forEach(function(k){
              var el = document.querySelector('input[name="fingerprint[' + k + ']"]');
              f[k] = el ? el.value : '';
            });
          f._csrf = (document.querySelector('meta[name=csrf-token]')||{}).content || '';
          return JSON.stringify(f);
        })()
    """.trimIndent()

    // ---------------------------------------------------------------- login

    /**
     * Logs in with the given credentials using the WebView, exactly like a
     * person would: load the form, let fp.js fill it, submit, wait for /dashboard.
     */
    suspend fun login(username: String, password: String): Boolean {
        val ok = runInPage(
            url = "$BASE/",
            timeoutMs = 90_000,
            script = READ_FINGERPRINT,
        ) { fpJson ->
            // This runs after the login page loaded; we still need to submit.
            fpJson
        }

        val fingerprint = ok ?: return finishLogin()

        return finishLogin(
            formScript = """
                (function(){
                  var u = document.querySelector('input[name=login]');
                  var p = document.querySelector('input[name=password]');
                  if (!u || !p) return 'none';
                  u.value = ${sqlString(username)};
                  p.value = ${sqlString(password)};
                  var btn = document.getElementById('login-submit-btn');
                  var form = u.closest('form');
                  if (form) form.submit();
                  return 'submitted';
                })()
            """.trimIndent(),
        )
    }

    private suspend fun finishLogin(formScript: String? = null): Boolean {
        if (formScript != null) {
            runInPage(url = "$BASE/", script = formScript) { it }
        }
        // Wait for Shkolo to land on the dashboard, then capture the ids.
        val landed = runInPage(
            url = "$BASE/dashboard",
            timeoutMs = 20_000,
            script = """
                (function(){
                  var p = document.querySelector('#pupil_id_field');
                  var c = document.querySelector('#class_year_id_field');
                  return JSON.stringify({
                    pupil: p ? p.value : null,
                    cls: c ? c.value : null,
                    href: location.href
                  });
                })()
            """.trimIndent(),
        ) {
            val o = org.json.JSONObject(it)
            pupilId = o.optString("pupil", "").ifBlank { null }
            classYearId = o.optString("cls", "").ifBlank { null }
            o.optString("href", "").contains("/dashboard")
        }

        cookieManager.flush()
        csrf = null
        return landed == true && isLoggedIn
    }

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

    private fun sqlString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "") + "'"

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
