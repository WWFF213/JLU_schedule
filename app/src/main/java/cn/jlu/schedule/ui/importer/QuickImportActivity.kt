package cn.jlu.schedule.ui.importer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.jlu.schedule.R
import cn.jlu.schedule.auth.CampusCookieJar
import cn.jlu.schedule.auth.TpassConfig
import cn.jlu.schedule.data.ImportedScheduleStorage
import cn.jlu.schedule.data.ScheduleRepository
import cn.jlu.schedule.parser.ScheduleImportCacheParser
import cn.jlu.schedule.remote.AutoImportCoordinator
import cn.jlu.schedule.remote.JwApiClient
import cn.jlu.schedule.remote.ScheduleRemoteSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import cn.jlu.schedule.ui.auth.LoginActivity
import cn.jlu.schedule.ui.theme.ThemePaletteProvider
import cn.jlu.schedule.ui.theme.UiFeedback
import cn.jlu.schedule.ui.theme.GlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一键导入（WebView 驱动）：复用内置浏览器里已登录的 Cookie，在隐藏 WebView 中打开
 * 智慧教育平台"我的课表"应用页，由页面自身请求课表接口；注入的 JS 钩子捕获接口响应后
 * 直接走与网页导入相同的解析入库管线。
 *
 * 兜底顺序：先直连课表接口（GET 可能直接回 JSON），失败则加载应用页；命中统一认证
 * 登录页时尝试用保存的密码静默重登并回写 Cookie，仍失败则引导手动登录。
 */
class QuickImportActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actionLogin: Button
    private lateinit var closeButton: Button

    private val capturedKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private val capturedFiles = java.util.Collections.synchronizedList(ArrayList<Pair<String, File>>())
    private val sessionDir by lazy { File(filesDir, "import_web_cache/quick_${System.currentTimeMillis()}") }
    private val handled = AtomicBoolean(false)
    private val importStarted = AtomicBoolean(false)
    private var silentLoginTried = false
    private var fallbackPageLoaded = false

    /** 会话过期时教务域不改 URL 直接返回 CAS 登录页 HTML，需在内容层识别后触发静默重登 */
    @Volatile
    private var casLoginHtmlServed = false
    private var fallbackFetchDone = false
    private var indexCounter = 0
    private var importJob: kotlinx.coroutines.Job? = null

    private val mode by lazy {
        if (intent.getBooleanExtra(EXTRA_CREATE_NEW, false)) {
            ImportedScheduleStorage.ImportMode.CREATE_NEW
        } else {
            ImportedScheduleStorage.ImportMode.OVERWRITE_ACTIVE
        }
    }
    private val newProfileName by lazy { intent.getStringExtra(EXTRA_NEW_NAME) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_import)

        val palette = ThemePaletteProvider.fromContext(this)
        findViewById<LinearLayout>(R.id.quickCard).background =
            GlassSurface.drawable(this, palette, GlassSurface.Variant.Strong, 28f)
        message = findViewById(R.id.quickMessage)
        progress = findViewById(R.id.quickProgress)
        actionLogin = findViewById(R.id.quickActionLogin)
        closeButton = findViewById(R.id.quickClose)
        UiFeedback.styleSecondaryButton(closeButton, palette)
        UiFeedback.stylePrimaryButton(actionLogin, palette)
        closeButton.setOnClickListener { finish() }
        actionLogin.setOnClickListener {
            actionLogin.isEnabled = false
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }

        webView = findViewById(R.id.quickWebView)
        setupWebView()

        // 预先把已有 Cookie 同步进 WebView
        JwApiClient.importAllWebViewCookies(this)
        JwApiClient.syncJarToWebView(this)

        scheduleTimeoutWatchdog()

        // 优先尝试原生极速拉取；若已有可用会话直接秒级入库
        tryNativeDirectFetch()

        webView.loadUrl(SCHEDULE_API_URL)
    }

    private fun tryNativeDirectFetch() {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = ScheduleRemoteSource.fetchScheduleNative(this@QuickImportActivity)
            res.onSuccess { fetches ->
                if (handled.get() || fetches.isEmpty()) return@onSuccess
                Log.i(TAG, "direct native schedule fetch ok: ${fetches.size} payloads")
                withContext(Dispatchers.Main) {
                    fetches.forEach { fetch ->
                        onPayloadCaptured(fetch.finalUrl, fetch.json)
                    }
                }
            }.onFailure {
                Log.w(TAG, "direct native schedule fetch failed: ${it.message}, fallback to webview")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = TpassConfig.USER_AGENT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(Bridge(), "QuickImportBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // 主文档拦截：下载 HTML 并把捕获钩子插到 <head> 后，
                // 保证页面任何脚本执行前 XHR/fetch 已被包住（消除注入时序竞态）
                if (handled.get() || !request.isForMainFrame) return null
                val url = request.url
                if (url.host != TpassConfig.IEDU_HOST || request.method != "GET") return null
                if (url.encodedPath?.endsWith(".do") != true) return null
                return runCatching { buildHookedDocumentResponse(url) }
                    .onFailure { Log.w(TAG, "intercept failed: ${it.message}") }
                    .getOrNull()
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (handled.get()) return
                val host = runCatching { android.net.Uri.parse(url).host ?: "" }.getOrDefault("")
                injectCaptureHook(view)
                if (casLoginHtmlServed) {
                    casLoginHtmlServed = false
                    onCasLoginPage(url)
                    return
                }
                when {
                    host == TpassConfig.CAS_HOST -> onCasLoginPage(url)
                    url.startsWith(SCHEDULE_API_URL) -> onApiPageFinished(view, url)
                    host == TpassConfig.IEDU_HOST && fallbackPageLoaded -> scheduleFallbackFetch(view)
                }
            }
        }
    }

    /** 第一跳：直接 GET 课表接口。返回 JSON 则完事，返回 HTML 则转应用页路由 */
    private fun onApiPageFinished(view: WebView, url: String) {
        if (fallbackPageLoaded) {
            injectCaptureHook(view)
            return
        }
        view.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { raw ->
            val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (handled.get()) return@evaluateJavascript
            if (ScheduleImportCacheParser.looksLikeSchedulePayload(url, text, text.length.toLong())) {
                onPayloadCaptured(url, text)
            } else {
                fallbackPageLoaded = true
                showMessage(getString(R.string.import_quick_progress_fetch))
                webView.post { webView.loadUrl(WDKB_APP_URL) }
            }
        }
    }

    /** 登录页：先试原生静默重登（凭据已存时），回写 Cookie 后重试；否则引导手动登录 */
    private fun onCasLoginPage(url: String) {
        if (silentLoginTried) {
            if (!handled.get()) needManualLogin()
            return
        }
        silentLoginTried = true
        showMessage(getString(R.string.import_quick_progress_relogin))
        lifecycleScope.launch {
            val result = JwApiClient.silentLogin(this@QuickImportActivity)
            if (result is cn.jlu.schedule.auth.CasLoginResult.Success) {
                JwApiClient.syncJarToWebView(this@QuickImportActivity)
                tryNativeDirectFetch()
                if (!handled.get()) webView.loadUrl(SCHEDULE_API_URL)
            } else {
                needManualLogin()
            }
        }
    }

    private fun injectCaptureHook(view: WebView) {
        view.evaluateJavascript(HOOK_JS, null)
    }

    /**
     * 下载主文档 HTML 并注入捕获钩子后返回给 WebView。
     * 复用 CookieManager 中的登录会话；JSON/非 HTML 响应原样透传。
     */
    private fun buildHookedDocumentResponse(target: android.net.Uri): WebResourceResponse {
        val connection = java.net.URL(target.toString()).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        CookieManager.getInstance().getCookie(target.toString())?.let {
            connection.setRequestProperty("Cookie", it)
        }
        connection.setRequestProperty("User-Agent", TpassConfig.USER_AGENT)
        connection.setRequestProperty("Referer", TpassConfig.IEDU_PORTAL_URL)
        connection.connect()
        val mimeType = connection.contentType?.substringBefore(';')?.trim() ?: "text/html"
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
        val body = stream.use { it.readBytes() }
        val headers = connection.headerFields
            ?.filterKeys { key -> key != null && !key.equals("Content-Length", true) && !key.equals("Content-Type", true) && !key.equals("Set-Cookie", true) }
            ?.mapValues { entry -> entry.value.joinToString(", ") }
        Log.i(TAG, "intercepted main doc $target -> $responseCode $mimeType len=${body.size}")

        // 把可能附带的 Set-Cookie 回写到 CookieManager
        connection.headerFields?.get("Set-Cookie")?.forEach { cookieVal ->
            CookieManager.getInstance().setCookie(target.toString(), cookieVal)
        }

        val text = String(body, Charsets.UTF_8)
        if (ScheduleImportCacheParser.looksLikeSchedulePayload(target.toString(), text, text.length.toLong())) {
            Log.i(TAG, "schedule payload directly intercepted from doc: ${text.length} bytes")
            runOnUiThread { onPayloadCaptured(target.toString(), text) }
        }

        if (!mimeType.contains("html", ignoreCase = true)) {
            return WebResourceResponse(mimeType, "UTF-8", body.inputStream()).apply {
                responseHeaders = headers
            }
        }
        var html = text
        if (html.contains("id=\"loginForm\"") || html.contains("id=\"lt\"")) {
            Log.i(TAG, "served doc is CAS login page ($target), will trigger silent relogin")
            casLoginHtmlServed = true
            return WebResourceResponse(mimeType, "UTF-8", html.byteInputStream()).apply {
                responseHeaders = headers
            }
        }
        if (!html.contains("__jluQuickHooked")) {
            val script = "<script>$HOOK_JS</script>"
            val headTag = Regex("(?i)<head[^>]*>").find(html)
            html = if (headTag != null) {
                StringBuilder(html).insert(headTag.range.last + 1, script).toString()
            } else {
                script + html
            }
        }
        return WebResourceResponse(mimeType, "UTF-8", html.byteInputStream()).apply {
            responseHeaders = headers
        }
    }

    /**
     * 页面自身请求已错过（注入前完成）或页面未自动请求时的兜底：
     * 直接在页面上下文里 fetch 课表接口（先 POST 后 GET），响应仍走钩子回传。
     */
    private fun scheduleFallbackFetch(view: WebView) {
        if (fallbackFetchDone || handled.get() || capturedFiles.isNotEmpty()) return
        fallbackFetchDone = true
        view.postDelayed({
            if (handled.get() || capturedFiles.isNotEmpty()) return@postDelayed
            Log.i(TAG, "page requests missed, fetching schedule API directly")
            view.evaluateJavascript(FALLBACK_FETCH_JS, null)
        }, 2500)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onDebug(text: String?) {
            Log.d(TAG, "hook: ${text.orEmpty().take(300)}")
        }

        @JavascriptInterface
        fun onNetworkResponse(url: String?, content: String?) {
            val safeUrl = url.orEmpty().trim()
            val text = content.orEmpty()
            if (safeUrl.isBlank() || handled.get()) return
            if (text.length < MIN_PAYLOAD_BYTES) return
            if (!ScheduleImportCacheParser.looksLikeSchedulePayload(safeUrl, text, text.length.toLong())) return
            onPayloadCaptured(safeUrl, text)
        }
    }

    /** 抓到课表 JSON：落盘去重，滚动静默期后统一解析入库 */
    private fun onPayloadCaptured(url: String, text: String) {
        val key = captureKey(url, text)
        synchronized(capturedKeys) {
            if (!capturedKeys.add(key)) return
        }
        synchronized(capturedFiles) {
            sessionDir.mkdirs()
            indexCounter += 1
            val file = File(sessionDir, "%04d_quick.json".format(indexCounter))
            file.writeText(text, Charsets.UTF_8)
            capturedFiles.add(url to file)
        }
        Log.i(TAG, "captured schedule payload #${indexCounter} (${text.length} bytes)")
        showMessage(getString(R.string.import_quick_progress_import))
        // 滚动静默期：页面可能分多次查询不同视图（周视图/整学期），每收到新捕获就重置计时
        // 捕获到 2+ 个载荷时缩短静默至 1.2 秒以迅速收口入库，避免用户面对转圈等待
        importJob?.cancel()
        importJob = lifecycleScope.launch {
            val quietMs = if (capturedFiles.size >= 2) 1200L else CAPTURE_QUIET_MS
            delay(quietMs)
            if (handled.get()) return@launch
            importAll()
        }
    }

    private suspend fun importAll() {
        // 防止静默期任务与超时看门狗并发执行同一导入
        if (!importStarted.compareAndSet(false, true)) return
        val entries = synchronized(capturedFiles) {
            capturedFiles.mapIndexed { index, (url, file) ->
                ScheduleImportCacheParser.CacheEntry(
                    url = url,
                    fileName = file.name,
                    filePath = file.absolutePath,
                    size = file.length().toInt(),
                    sequence = index + 1
                )
            }
        }
        val phase = AutoImportCoordinator.importCaptured(this, entries, mode, newProfileName)
        if (handled.get()) return
        handled.set(true)
        progress.visibility = View.GONE
        when (phase) {
            is AutoImportCoordinator.Phase.Done -> {
                showMessage(
                    "导入成功：${phase.courseCount} 门课程" +
                        (phase.newProfileName?.let { "（新课表 $it）" } ?: "")
                )
                setResult(RESULT_OK)
                webView.postDelayed({ finish() }, 1200)
            }
            else -> {
                showMessage(phase.errorMessage())
            }
        }
    }

    private fun needManualLogin() {
        if (handled.get()) return
        handled.set(true)
        progress.visibility = View.GONE
        showMessage("登录已过期，请先登录校园账号")
        actionLogin.isEnabled = true
        actionLogin.visibility = View.VISIBLE
    }

    private fun scheduleTimeoutWatchdog() {
        lifecycleScope.launch {
            delay(TIMEOUT_MS)
            if (handled.get()) return@launch
            if (capturedFiles.isNotEmpty()) {
                // 已有捕获但静默期未结束（页面持续查询）：直接收口导入
                importAll()
            } else {
                handled.set(true)
                progress.visibility = View.GONE
                showMessage("获取课表超时，请确认已登录且网络可用后重试")
            }
        }
    }

    private fun showMessage(text: String) {
        runOnUiThread { message.text = text }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            silentLoginTried = false
            handled.set(false)
            importStarted.set(false)
            progress.visibility = View.VISIBLE
            actionLogin.visibility = View.GONE
            JwApiClient.importAllWebViewCookies(this)
            JwApiClient.syncJarToWebView(this)
            tryNativeDirectFetch()
            webView.loadUrl(SCHEDULE_API_URL)
        }
    }

    override fun onDestroy() {
        webView.apply {
            loadUrl("about:blank")
            onPause()
        }
        sessionDir.deleteRecursively()
        super.onDestroy()
    }

    private fun AutoImportCoordinator.Phase.errorMessage(): String = when (this) {
        is AutoImportCoordinator.Phase.Failed -> message
        else -> "导入失败"
    }

    companion object {
        private const val TAG = "QuickImport"
        private const val MIN_PAYLOAD_BYTES = 80
        private const val TIMEOUT_MS = 30_000L

        /** 最后一次捕获后的静默等待，页面查完不同视图（周/学期）再统一解析 */
        private const val CAPTURE_QUIET_MS = 5_000L
        private const val REQ_LOGIN = 4001

        /** 我的课表应用页（金智 eMAP 标准路由），页面自身会请求课表接口 */
        const val WDKB_APP_URL = "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/*default/index.do"

        /** 课表查询接口：金智平台 GET 直返 JSON（带会话时） */
        const val SCHEDULE_API_URL =
            "https://iedu.jlu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do"

        const val EXTRA_CREATE_NEW = "extra_create_new"
        const val EXTRA_NEW_NAME = "extra_new_name"

        private val HOOK_JS = """
            (function(){
              if(window.__jluQuickHooked) return; window.__jluQuickHooked = true;
              function send(url, text){
                try { QuickImportBridge.onNetworkResponse(url, text); } catch(e) {}
              }
              var origOpen = XMLHttpRequest.prototype.open;
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m, u){ try { QuickImportBridge.onDebug('xhr '+m+' '+u); } catch(e) {} this.__u = u; return origOpen.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function(){
                this.addEventListener('load', function(){
                  try { send(this.__u || '', this.responseText || ''); } catch(e) {}
                });
                return origSend.apply(this, arguments);
              };
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function(){
                  var u = arguments[0]; var url = (typeof u === 'string') ? u : (u && u.url) || '';
                  try { QuickImportBridge.onDebug('fetch '+url); } catch(e) {}
                  return origFetch.apply(this, arguments).then(function(resp){
                    try { resp.clone().text().then(function(t){ send(url, t); }); } catch(e) {}
                    return resp;
                  });
                };
              }
              try { QuickImportBridge.onDebug('hook installed'); } catch(e) {}
            })();
        """.trimIndent()

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 同一接口可能连续返回不同周/学期数据，必须比较完整响应。 */
        fun captureKey(url: String, content: String): String = sha1("$url|$content")

        fun start(activity: android.app.Activity, createNew: Boolean, newName: String?, requestCode: Int) {
            activity.startActivityForResult(
                Intent(activity, QuickImportActivity::class.java)
                    .putExtra(EXTRA_CREATE_NEW, createNew)
                    .putExtra(EXTRA_NEW_NAME, newName),
                requestCode
            )
        }

        /** 页面上下文内主动请求课表接口：XHR POST（金智模块方法标准），失败带状态码回传 */
        private val FALLBACK_FETCH_JS = """
            (function(){
              var url = '$SCHEDULE_API_URL';
              function grab(method){
                return new Promise(function(resolve){
                  var xhr = new XMLHttpRequest();
                  xhr.open(method, url, true);
                  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
                  xhr.onreadystatechange = function(){
                    if (xhr.readyState !== 4) return;
                    try {
                      QuickImportBridge.onDebug('fallback '+method+' status='+xhr.status+' len='+(xhr.responseText||'').length);
                      QuickImportBridge.onNetworkResponse(url, xhr.responseText || '');
                    } catch(e) { try { QuickImportBridge.onDebug('fallback read error='+e); } catch(e2) {} }
                    resolve();
                  };
                  xhr.onerror = function(){
                    try { QuickImportBridge.onDebug('fallback '+method+' network error'); } catch(e) {}
                    resolve();
                  };
                  xhr.send('');
                });
              }
              grab('POST').then(function(){ return grab('GET'); });
            })();
        """.trimIndent()
    }
}
