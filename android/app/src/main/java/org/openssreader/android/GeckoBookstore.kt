package org.openssreader.android

import android.content.Context
import android.app.AlertDialog
import android.widget.EditText
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

private const val GECKO_BOOKSTORE_HOME = "https://www.sslibrary.com/"
private const val GECKO_LOG_TAG = "OSR_GECKO_BOOKSTORE"

/** A single Firefox engine instance per app process. GeckoView cookies and
 * session storage use their own browser profile; they are never exported to
 * OpenSSReader's PDZ downloader or Android WebView. */
private object GeckoEngine {
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        context.applicationContext
    ).also { runtime = it }
}

/** Activity-owned browser state. A Compose tab switch only detaches the view;
 * the GeckoSession stays open, retaining real DOM, navigation and form state.
 * Screen rotation is handled by MainActivity.configChanges. */
internal class GeckoBookstoreState {
    var mainSession: GeckoSession? = null
    var popupSession by mutableStateOf<GeckoSession?>(null)
    var mainView: GeckoView? = null
    var popupView: GeckoView? = null
    var loading by mutableStateOf(true)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var mainCanBack = false
    var mainCanForward = false
    var isLoginPage by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var desktopMode by mutableStateOf(true)

    fun active(): GeckoSession? = popupSession ?: mainSession

    fun closePopup() {
        val old = popupSession
        popupSession = null
        popupView?.let { view ->
            runCatching { view.releaseSession() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        popupView = null
        if (old != null) runCatching { if (old.isOpen) old.close() }
        canBack = mainCanBack
        canForward = mainCanForward
        isLoginPage = false
    }

    fun changeDesktopMode(enabled: Boolean) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        val agent = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        val viewport = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        listOfNotNull(mainSession, popupSession).forEach {
            it.settings.setUserAgentMode(agent)
            it.settings.setViewportMode(viewport)
            it.reload()
        }
    }

    fun release() {
        closePopup()
        mainView?.let { view ->
            runCatching { view.releaseSession() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        mainView = null
        mainSession?.let { runCatching { if (it.isOpen) it.close() } }
        mainSession = null
    }
}

/** No JavaScript injection. No custom viewport tags. No CSS movement of login
 * fields. Book links alone are delegated to the existing PDZ download engine. */
@Composable
internal fun GeckoBookstorePage(state: GeckoBookstoreState, onBookLink: (String) -> Unit) {
    val active = state.active()
    BackHandler(enabled = state.popupSession != null || state.canBack) {
        if (state.canBack) active?.goBack() else state.closePopup()
    }

    fun isLoginUrl(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase().orEmpty()
        val path = u.path.orEmpty().lowercase()
        return host.startsWith("passport") || path.contains("login") || path.contains("signin")
    }

    fun handOffBook(url: String): Boolean {
        if (!url.startsWith("book://", ignoreCase = true)) return false
        // Parsing and institution /ssid validation remain the responsibility
        // of OsrViewModel.acceptBookLink(). Never fetch a book URI in Gecko.
        onBookLink(url)
        state.closePopup()
        return true
    }

    fun makeSession(context: Context, popup: Boolean): GeckoSession {
        val desktop = state.desktopMode
        val s = GeckoSession(GeckoSessionSettings.Builder()
            .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build())
        s.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                if (handOffBook(request.uri)) return GeckoResult.deny()
                val scheme = runCatching { Uri.parse(request.uri).scheme?.lowercase() }.getOrNull()
                return if (scheme == "https" || scheme == "http" || scheme == "about") {
                    null // Native Gecko navigation, redirects and login are untouched.
                } else {
                    Log.w(GECKO_LOG_TAG, "blocked_navigation_scheme=${scheme ?: "none"}")
                    GeckoResult.deny()
                }
            }

            override fun onSubframeLoadRequest(session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                // A frame is not permitted to silently queue a PDZ download.
                return if (request.uri.startsWith("book://", ignoreCase = true))
                    GeckoResult.deny() else null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (handOffBook(uri)) return null
                if (!uri.startsWith("https://", true) && !uri.startsWith("http://", true)
                    && !uri.isBlank() && uri != "about:blank") return null
                state.closePopup()
                // Gecko opens the returned session itself. Never call open() here.
                val child = makeSession(context, true)
                state.popupSession = child
                state.canBack = false
                state.canForward = false
                return GeckoResult.fromValue(child)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (session === state.mainSession) state.mainCanBack = canGoBack
                if (session === state.active()) state.canBack = canGoBack
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                if (session === state.mainSession) state.mainCanForward = canGoForward
                if (session === state.active()) state.canForward = canGoForward
            }
            override fun onLoadError(session: GeckoSession, uri: String?,
                error: WebRequestError): GeckoResult<String>? {
                // Fallback for browsers that report unknown book:// schemes as
                // external protocol errors rather than ordinary load requests.
                if (uri != null && handOffBook(uri)) return null
                state.loading = false
                state.errorMessage = "Firefox 网页加载失败。请检查网络、SSL 证书及机构登录状态。"
                return null // Never bypass certificate failures.
            }
        })
        s.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                state.loading = true
                state.progress = 0
                state.errorMessage = null
                if (session === state.active()) state.isLoginPage = isLoginUrl(url)
                val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().take(80)
                Log.i(GECKO_LOG_TAG, "page_start host=$host popup=$popup")
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session === state.active()) state.progress = progress
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (session === state.active()) {
                    state.loading = false
                    if (!success && state.errorMessage == null)
                        state.errorMessage = "Firefox 页面未能完成加载；请检查网络或机构权限。"
                }
            }
        })
        s.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                if (session === state.popupSession) state.closePopup()
            }
            override fun onCrash(session: GeckoSession) {
                state.errorMessage = "Firefox 网页进程已停止，请重新打开书城。"
            }
        })
        // Native, user-visible JavaScript dialogs. The app never reads or logs
        // the submitted input; it is returned directly to the browser engine.
        s.setPromptDelegate(object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val response = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                AlertDialog.Builder(context)
                    .setTitle(prompt.title ?: "网站提示")
                    .setMessage(prompt.message.orEmpty())
                    .setPositiveButton("确定") { _, _ -> response.complete(prompt.dismiss()) }
                    .setOnCancelListener { response.complete(prompt.dismiss()) }
                    .show()
                return response
            }
            override fun onButtonPrompt(session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val response = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                AlertDialog.Builder(context)
                    .setTitle(prompt.title ?: "请确认")
                    .setMessage(prompt.message.orEmpty())
                    .setPositiveButton("确定") { _, _ -> response.complete(
                        prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                    .setNegativeButton("取消") { _, _ -> response.complete(
                        prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                    .setOnCancelListener { response.complete(prompt.dismiss()) }
                    .show()
                return response
            }
            override fun onTextPrompt(session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val response = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val field = EditText(context).apply { setText(prompt.defaultValue.orEmpty()) }
                AlertDialog.Builder(context)
                    .setTitle(prompt.title ?: "网站输入")
                    .setMessage(prompt.message.orEmpty())
                    .setView(field)
                    .setPositiveButton("确定") { _, _ -> response.complete(
                        prompt.confirm(field.text.toString())) }
                    .setNegativeButton("取消") { _, _ -> response.complete(prompt.dismiss()) }
                    .setOnCancelListener { response.complete(prompt.dismiss()) }
                    .show()
                return response
            }
        })
        if (!popup) {
            s.open(GeckoEngine.get(context))
        }
        return s
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.isLoginPage) "机构登录" else "书城",
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (state.popupSession != null) IconButton(onClick = { state.closePopup() }) {
                Icon(Icons.Default.Close, "关闭登录弹窗")
            }
            IconButton(onClick = { state.active()?.goBack() }, enabled = state.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退")
            }
            IconButton(onClick = { state.active()?.goForward() }, enabled = state.canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进")
            }
            IconButton(onClick = { state.closePopup(); state.mainSession?.loadUri(GECKO_BOOKSTORE_HOME) }) {
                Icon(Icons.Default.Home, "书城首页")
            }
            IconButton(onClick = { state.active()?.reload() }) {
                Icon(Icons.Default.Refresh, "刷新")
            }
            IconButton(onClick = { state.changeDesktopMode(!state.desktopMode) }) {
                Icon(if (state.desktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                    if (state.desktopMode) "切换至移动版网站" else "切换至桌面版网站")
            }
        }
        if (state.loading) LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(), progress = { (state.progress / 100f).coerceIn(0f, 1f) })
        state.errorMessage?.let { problem ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { state.errorMessage = null; state.active()?.reload() }) { Text("重试") }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val newlyCreated = state.mainSession == null
                val browser = state.mainSession ?: makeSession(context, false).also {
                    state.mainSession = it
                }
                val view = state.mainView ?: GeckoView(context).also { state.mainView = it }
                (view.parent as? ViewGroup)?.removeView(view)
                if (view.session !== browser) {
                    if (view.session != null) view.releaseSession()
                    view.setSession(browser)
                }
                if (newlyCreated) browser.loadUri(GECKO_BOOKSTORE_HOME)
                view
            }, update = { view ->
                if (state.mainSession == null) {
                    // Re-created after a process restart: cookies persist in Gecko.
                    state.mainSession = makeSession(view.context, false)
                    view.setSession(state.mainSession!!)
                }
            })
            val popup = state.popupSession
            if (popup != null) AndroidView(modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val view = state.popupView ?: GeckoView(context).also { state.popupView = it }
                    (view.parent as? ViewGroup)?.removeView(view)
                    if (view.session !== popup) {
                        if (view.session != null) view.releaseSession()
                        view.setSession(popup)
                    }
                    view
                }, update = { view ->
                    if (view.session !== popup && popup.isOpen) {
                        if (view.session != null) view.releaseSession()
                        view.setSession(popup)
                    }
                })
        }
    }
}
