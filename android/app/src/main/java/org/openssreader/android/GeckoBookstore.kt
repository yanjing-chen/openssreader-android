package org.openssreader.android

import android.content.Context
import android.app.AlertDialog
import android.widget.EditText
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Each tab owns a GeckoSession and its navigation state. Sessions are kept
 * alive across tab switches; exactly one GeckoView is attached at a time.
 * The root search page is never replaced by an SSLibrary detail popup. */
internal class BookstoreTab(
    val id: Int,
    val session: GeckoSession,
    val parentId: Int?,
    val root: Boolean
) {
    var title by mutableStateOf(if (root) "书城首页" else "图书详情")
    var loading by mutableStateOf(!root)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var isLoginPage by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
}

/** Activity-owned session list, independent of the app's bottom navigation. */
internal class GeckoBookstoreState {
    val tabs = mutableStateListOf<BookstoreTab>()
    var selectedTabId by mutableIntStateOf(0)
    var browserView: GeckoView? = null
    var desktopMode by mutableStateOf(true)
    private var nextId = 1

    fun tabFor(session: GeckoSession): BookstoreTab? = tabs.firstOrNull { it.session === session }
    fun activeTab(): BookstoreTab? = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.firstOrNull()
    fun active(): GeckoSession? = activeTab()?.session
    val isLoginPage: Boolean get() = activeTab()?.isLoginPage == true
    fun root(): BookstoreTab? = tabs.firstOrNull { it.root }

    fun installRoot(session: GeckoSession): BookstoreTab {
        root()?.let { return it }
        val tab = BookstoreTab(0, session, null, true)
        tabs.add(tab)
        selectedTabId = tab.id
        return tab
    }

    fun addChild(session: GeckoSession, parentId: Int?): BookstoreTab {
        val child = BookstoreTab(nextId++, session, parentId, false)
        tabs.add(child)
        selectedTabId = child.id
        Log.i(GECKO_LOG_TAG, "tab_open id=${child.id} count=${tabs.size}")
        return child
    }

    fun selectTab(id: Int) {
        if (tabs.any { it.id == id }) selectedTabId = id
    }

    fun closeTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id && !it.root } ?: return
        val wasSelected = selectedTabId == id
        // An active tab must be detached before its GeckoSession is closed.
        if (browserView?.session === tab.session) runCatching { browserView?.releaseSession() }
        tabs.remove(tab)
        if (tab.session.isOpen) runCatching { tab.session.close() }
        if (wasSelected) {
            val parentStillOpen = tabs.any { it.id == tab.parentId }
            selectedTabId = if (parentStillOpen) tab.parentId!! else root()?.id ?: 0
        }
        Log.i(GECKO_LOG_TAG, "tab_close id=$id count=${tabs.size}")
    }

    fun changeDesktopMode(enabled: Boolean) {
        if (desktopMode == enabled) return
        desktopMode = enabled
        val agent = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        val viewport = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        tabs.forEach { tab ->
            tab.session.settings.setUserAgentMode(agent)
            tab.session.settings.setViewportMode(viewport)
            tab.session.reload()
        }
    }

    fun release() {
        browserView?.let { view ->
            runCatching { view.releaseSession() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        browserView = null
        tabs.forEach { tab -> runCatching { if (tab.session.isOpen) tab.session.close() } }
        tabs.clear()
        selectedTabId = 0
    }
}

/** Firefox renders the original website; the app only handles native tabs and book://. */
@Composable
internal fun GeckoBookstorePage(state: GeckoBookstoreState, onBookLink: (String) -> Unit) {
    val selected = state.activeTab()
    BackHandler(enabled = (selected?.canBack == true) || (selected != null && !selected.root)) {
        val tab = state.activeTab()
        if (tab?.canBack == true) tab.session.goBack()
        else if (tab != null && !tab.root) state.closeTab(tab.id)
    }

    fun isLoginUrl(url: String): Boolean {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase().orEmpty()
        val path = u.path.orEmpty().lowercase()
        return host.startsWith("passport") || path.contains("login") || path.contains("signin")
    }

    fun handOffBook(url: String): Boolean {
        if (!url.startsWith("book://", ignoreCase = true)) return false
        // The original tab remains open and usable after the PDZ handoff.
        Log.i(GECKO_LOG_TAG, "book_handoff tab=${state.activeTab()?.id ?: -1}")
        onBookLink(url)
        return true
    }

    fun makeSession(context: Context, isRoot: Boolean): GeckoSession {
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
                val parsed = runCatching { Uri.parse(request.uri) }.getOrNull()
                val scheme = parsed?.scheme?.lowercase()
                val host = parsed?.host?.take(80).orEmpty()
                Log.i(GECKO_LOG_TAG, "navigation tab=${state.tabFor(session)?.id ?: -1} " +
                    "target=${request.target} redirect=${request.isRedirect} gesture=${request.hasUserGesture} " +
                    "scheme=${scheme ?: "none"} host=$host")
                return if (scheme == "http" || scheme == "https" || scheme == "about") {
                    null // Let Firefox follow the website's own navigation normally.
                } else {
                    Log.w(GECKO_LOG_TAG, "blocked_navigation_scheme=${scheme ?: "none"}")
                    GeckoResult.deny()
                }
            }

            override fun onSubframeLoadRequest(session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                return if (request.uri.startsWith("book://", ignoreCase = true))
                    GeckoResult.deny() else null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (handOffBook(uri)) return null
                val u = runCatching { Uri.parse(uri) }.getOrNull()
                val scheme = u?.scheme?.lowercase()
                if (uri.isNotBlank() && scheme != "http" && scheme != "https" && uri != "about:blank") {
                    Log.w(GECKO_LOG_TAG, "blocked_new_tab_scheme=${scheme ?: "none"}")
                    return null
                }
                if (state.tabs.size >= 12) {
                    state.activeTab()?.errorMessage = "已打开过多书城标签，请先关闭不用的图书详情页。"
                    return null
                }
                // Returned GeckoSession MUST be new and unopened; GeckoView
                // runtime opens it and handles the target URI after the callback.
                val child = makeSession(context, false)
                state.addChild(child, state.tabFor(session)?.id)
                Log.i(GECKO_LOG_TAG, "new_window_created parent=${state.tabFor(session)?.id ?: -1} " +
                    "scheme=${scheme ?: "none"} host=${u?.host?.take(80).orEmpty()}")
                return GeckoResult.fromValue(child)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                state.tabFor(session)?.canBack = canGoBack
            }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                state.tabFor(session)?.canForward = canGoForward
            }
            override fun onLoadError(session: GeckoSession, uri: String?,
                error: WebRequestError): GeckoResult<String>? {
                if (uri != null && handOffBook(uri)) return null
                val tab = state.tabFor(session)
                val host = uri?.let { runCatching { Uri.parse(it).host }.getOrNull() }.orEmpty().take(80)
                Log.w(GECKO_LOG_TAG, "load_error tab=${tab?.id ?: -1} host=$host")
                tab?.loading = false
                tab?.errorMessage = "Firefox 网页加载失败，请检查网络、SSL 证书或机构登录状态。"
                return null // Never bypass a certificate error.
            }
        })
        s.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                val tab = state.tabFor(session) ?: return
                tab.loading = true
                tab.progress = 0
                tab.errorMessage = null
                tab.isLoginPage = isLoginUrl(url)
                val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().take(80)
                Log.i(GECKO_LOG_TAG, "page_start tab=${tab.id} host=$host")
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                state.tabFor(session)?.progress = progress
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                val tab = state.tabFor(session) ?: return
                Log.i(GECKO_LOG_TAG, "page_stop tab=${tab.id} success=$success")
                tab.loading = false
                if (!success && tab.errorMessage == null)
                    tab.errorMessage = "Firefox 页面未能完成加载，请检查网络或机构权限。"
            }
        })
        s.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tab = state.tabFor(session) ?: return
                if (!title.isNullOrBlank()) tab.title = title.take(90)
            }
            override fun onCloseRequest(session: GeckoSession) {
                state.tabFor(session)?.let { tab -> if (!tab.root) state.closeTab(tab.id) }
            }
            override fun onCrash(session: GeckoSession) {
                val tab = state.tabFor(session) ?: return
                Log.w(GECKO_LOG_TAG, "process_crash tab=${tab.id}")
                if (!tab.root) {
                    state.closeTab(tab.id)
                    state.activeTab()?.errorMessage = "Firefox 图书详情标签已关闭，原搜索结果仍保留。"
                } else tab.errorMessage = "Firefox 网页进程已停止，请重新打开书城。"
            }
        })
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
        if (isRoot) s.open(GeckoEngine.get(context))
        return s
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected?.isLoginPage == true) "机构登录" else "书城",
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            IconButton(enabled = selected?.canBack == true,
                onClick = { state.active()?.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退")
            }
            IconButton(enabled = selected?.canForward == true,
                onClick = { state.active()?.goForward() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进")
            }
            IconButton(onClick = {
                state.root()?.let { root -> state.selectTab(root.id); root.session.loadUri(GECKO_BOOKSTORE_HOME) }
            }) { Icon(Icons.Default.Home, "书城首页") }
            IconButton(onClick = { state.active()?.reload() }) { Icon(Icons.Default.Refresh, "刷新") }
            IconButton(onClick = { state.changeDesktopMode(!state.desktopMode) }) {
                Icon(if (state.desktopMode) Icons.Default.DesktopWindows else Icons.Default.PhoneAndroid,
                    if (state.desktopMode) "切换至移动版网站" else "切换至桌面版网站")
            }
        }
        if (state.tabs.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.tabs.forEach { tab ->
                    val activeTab = tab.id == state.selectedTabId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (activeTab) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { state.selectTab(tab.id) },
                                modifier = Modifier.widthIn(max = 175.dp)) {
                                Text(if (tab.root) "搜索结果" else tab.title,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!tab.root) IconButton(onClick = { state.closeTab(tab.id) },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "关闭${tab.title}标签", modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }
        if (selected != null && selected.loading) LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { (selected.progress / 100f).coerceIn(0f, 1f) }
        )
        if (selected != null) selected.errorMessage?.let { problem ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { selected.errorMessage = null; selected.session.reload() }) { Text("重试") }
            }
        }
        // One GeckoView surface, regardless of the number of live sessions.
        // No transparent overlapping view can eat touches on the search page.
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            val isNew = state.root() == null
            // Both branches must produce a BookstoreTab, not an Any from
            // mixing BookstoreTab and GeckoSession in an Elvis expression.
            val root: BookstoreTab = state.root()
                ?: state.installRoot(makeSession(context, true))
            val view = state.browserView ?: GeckoView(context).also { state.browserView = it }
            (view.parent as? ViewGroup)?.removeView(view)
            if (view.session !== root.session) {
                if (view.session != null) view.releaseSession()
                view.setSession(root.session)
            }
            if (isNew) root.session.loadUri(GECKO_BOOKSTORE_HOME)
            view
        }, update = { view ->
            val tab = state.activeTab() ?: return@AndroidView
            if (view.session !== tab.session) {
                if (view.session != null) view.releaseSession()
                view.setSession(tab.session)
            }
        })
    }
}
