package org.openssreader.android

import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openssreader.android.core.PdzCatalogReader
import java.io.File
import kotlin.math.min
import kotlin.math.abs

private const val TAB_BOOKSTORE = 0
private const val TAB_SHELF = 1
private const val TAB_DOWNLOAD = 2
private const val TAB_SETTINGS = 3


class MainActivity : ComponentActivity() {
    private val model by viewModels<OsrViewModel>()
    private val bookstoreSession = GeckoBookstoreState()
    private val allFilesSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshStoragePermission()
    }
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            model.message("本系统无法申请 Android 11+ 所有文件访问，请在设置中使用自选目录")
            return
        }
        try {
            allFilesSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                allFilesSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (exc: Exception) { model.message("无法打开存储授权设置：${exc.message}") }
        }
    }
    private var pendingExport: ShelfBook? = null
    private var pendingStorageKind: PublicBookStorage.Kind? = null
    private val chooseStorage = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = pendingStorageKind
        pendingStorageKind = null
        if (uri != null && kind != null) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.chooseStorage(kind, uri)
            } catch (exc: Exception) { model.message("目录授权失败：${exc.message}") }
        }
    }
    private val importChooser = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri, contentResolver)
    }
    private val pdzSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingExport
        if (uri != null && pending != null) model.export(pending, uri, false, contentResolver)
        pendingExport = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app uses a light background. Dark system status icons remain readable
        // on narrow Android phones where the status bar is transparent.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        pendingStorageKind = savedInstanceState?.getString("pending-storage-kind")?.let {
            runCatching { PublicBookStorage.Kind.valueOf(it) }.getOrNull()
        }
        savedInstanceState?.getString("pending-export-id")?.let { id ->
            pendingExport = model.books.value.firstOrNull { it.id == id }
        }
        // A rotation recreates the Activity with the previous ACTION_VIEW Intent.
        // Never re-run a book:// download or import just because the screen rotated.
        // New external links still arrive via onNewIntent().
        if (savedInstanceState == null) handleExternalIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xff2164bd), secondary = Color(0xff55718e),
                background = Color(0xfff8f9fd), surface = Color(0xfff8f9fd))) {
                AppScreen(model, bookstoreSession,
                    openImport = { importChooser.launch(arrayOf("*/*")) },
                    requestFileAccess = ::requestAllFilesAccess,
                    chooseStorage = { kind ->
                        pendingStorageKind = kind
                        chooseStorage.launch(null)
                    },
                    export = { book ->
                        // Keep the raw PDZ export; PDF files are already mirrored
                        // to the configured storage folder after conversion.
                        if (book.type == "PDZ") {
                            pendingExport = book
                            pdzSaver.launch(book.filename)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshStoragePermission()
    }

    // MainActivity handles orientation changes without recreating the embedded
    // GeckoSession or the in-progress institutional login form.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStorageKind?.let { outState.putString("pending-storage-kind", it.name) }
        pendingExport?.let { book -> outState.putString("pending-export-id", book.id) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        bookstoreSession.release()
        super.onDestroy()
    }

    private fun handleExternalIntent(input: Intent?) {
        when (input?.action) {
            Intent.ACTION_VIEW -> {
                val uri = input.data ?: return
                if (uri.scheme.equals("book", true)) model.acceptBookLink(uri.toString())
                else if (uri.scheme.equals("content", true)) model.import(uri, contentResolver)
                else model.message("仅允许从系统文件选择器导入图书")
            }
            Intent.ACTION_SEND -> input.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                if (text.startsWith("book://", true)) model.acceptBookLink(text)
                // book:// is the supported institution browser handoff.
                // A browser's ordinary HTTPS page remains inside that browser.
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(model: OsrViewModel, bookstoreSession: GeckoBookstoreState, openImport: () -> Unit,
    requestFileAccess: () -> Unit, chooseStorage: (PublicBookStorage.Kind) -> Unit, export: (ShelfBook) -> Unit) {
    val books by model.books.collectAsState()
    val favorites by model.favorites.collectAsState()
    val labels by model.labels.collectAsState()
    val rootAccess by model.rootStorageGranted.collectAsState()
    val tasks by model.transfers.collectAsState()
    val grid by model.grid.collectAsState()
    val bookshelfSort by model.bookshelfSort.collectAsState()
    val coverWidth by model.coverWidth.collectAsState()
    val fields by model.fields.collectAsState()
    val notification by model.message.collectAsState()
    val bookTabNonce by model.downloadTabNonce.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(TAB_SHELF) }
    var section by rememberSaveable { mutableStateOf("all") }
    var labelDialogId by remember { mutableStateOf<String?>(null) }
    var labelDialogText by remember { mutableStateOf("") }
    var deleteLabelId by remember { mutableStateOf<String?>(null) }
    var promptRootAccess by remember { mutableStateOf(true) }
    // A book:// event is consumed exactly once. A rotation must NOT replay
    // the historical event and throw an open reader into Downloads.
    var consumedDownloadNonce by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(bookTabNonce) {
        if (bookTabNonce > consumedDownloadNonce) {
            consumedDownloadNonce = bookTabNonce
            tab = TAB_DOWNLOAD
        }
    }
    var search by remember { mutableStateOf("") }
    var activeBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeBook = books.firstOrNull { it.id == activeBookId }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notification) {
        if (notification.isNotBlank()) {
            snackbar.showSnackbar(notification)
            model.dismissMessage()
        }
    }
    if (activeBook != null) {
        ReaderOrInfo(model, activeBook, onBack = { activeBookId = null }, export = export)
        return
    }
    if (promptRootAccess && !rootAccess && Build.VERSION.SDK_INT >= 30) {
        AlertDialog(onDismissRequest = { promptRootAccess = false },
            title = { Text("保存图书到存储根目录") },
            text = { Text("为了自动创建内部共享存储/OpenSSReader/pdz 和 pdf，" +
                "需要你在系统设置中允许 OpenSSReader『管理所有文件』。" +
                "这不是 Root 权限；不授权也可以使用应用内部书架及手动选择文件夹。") },
            confirmButton = { TextButton(onClick = { promptRootAccess = false; requestFileAccess() }) {
                Text("前往授权") } },
            dismissButton = { TextButton(onClick = { promptRootAccess = false }) { Text("稍后") } })
    }
    if (labelDialogId != null) {
        val editId = labelDialogId
        AlertDialog(onDismissRequest = { labelDialogId = null },
            title = { Text(if (editId == "new") "新建标签" else "重命名标签") },
            text = { OutlinedTextField(labelDialogText, onValueChange = { labelDialogText = it.take(40) },
                singleLine = true, label = { Text("标签名称") }) },
            confirmButton = { TextButton(onClick = {
                if (editId == "new") model.createLabel(labelDialogText)
                else if (editId != null) model.renameLabel(editId, labelDialogText)
                labelDialogId = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { labelDialogId = null }) { Text("取消") } })
    }
    if (deleteLabelId != null) {
        val id = deleteLabelId
        val title = labels.firstOrNull { it.id == id }?.title ?: ""
        AlertDialog(onDismissRequest = { deleteLabelId = null },
            title = { Text("删除标签？") },
            text = { Text("将删除『$title』标签及其归类关系，但不会删除任何 PDZ、PDF 或书架图书。") },
            confirmButton = { TextButton(onClick = {
                if (id != null) {
                    model.deleteLabel(id)
                    if (section == "tag:$id") section = "all"
                }
                deleteLabelId = null
            }) { Text("删除标签") } },
            dismissButton = { TextButton(onClick = { deleteLabelId = null }) { Text("取消") } })
    }
    val activeDownloadCount = tasks.count { it.stage in setOf(
        "尝试直连", "等待授权", "准备下载", "下载中", "等待解码", "解码中") }
    // Keep authentication pages full-height instead of clipping the desktop form.
    val bookstoreLoginVisible = tab == TAB_BOOKSTORE && bookstoreSession.isLoginPage
    // Free additional vertical room for desktop SSO forms while typing.
    val bookstoreKeyboardVisible = tab == TAB_BOOKSTORE &&
        WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!bookstoreLoginVisible) TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.mipmap.ic_launcher), contentDescription = null,
                    modifier = Modifier.size(36.dp))
                Text(when (tab) {
                    TAB_SHELF -> "书架"
                    TAB_BOOKSTORE -> "书城"
                    TAB_DOWNLOAD -> "下载"
                    else -> "设置"
                }, fontWeight = FontWeight.Bold)
            } }, actions = {
                if (tab == TAB_SHELF) {
                    if (books.isNotEmpty()) IconButton(onClick = openImport) {
                        Icon(Icons.Default.Add, "手动导入图书")
                    }
                    IconButton(onClick = { model.setGrid(!grid) }) {
                        Icon(if (grid) Icons.Default.List else Icons.Default.GridView, "切换书架显示")
                    }
                }
            })
        },
        bottomBar = {
            if (!bookstoreKeyboardVisible && !bookstoreLoginVisible) NavigationBar {
                NavigationBarItem(selected = tab == TAB_BOOKSTORE, onClick = { tab = TAB_BOOKSTORE },
                    icon = { Icon(Icons.Default.Storefront, null) }, label = { Text("书城") })
                NavigationBarItem(selected = tab == TAB_SHELF, onClick = { tab = TAB_SHELF },
                    icon = { Icon(Icons.Default.MenuBook, null) }, label = { Text("书架") })
                NavigationBarItem(selected = tab == TAB_DOWNLOAD, onClick = { tab = TAB_DOWNLOAD },
                    icon = { Icon(Icons.Default.Download, null) }, label = { Text("下载") })
                NavigationBarItem(selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = if (tab == TAB_BOOKSTORE) 0.dp else 14.dp)) {
            when (tab) {
                TAB_BOOKSTORE -> GeckoBookstorePage(state = bookstoreSession,
                    onBookLink = model::acceptBookLink)
                TAB_SHELF -> {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // Use real available Compose width (dp), not orientation or
                        // device model. Compact windows must never keep two columns.
                        val compact = maxWidth < 600.dp
                        val wideSidebar = maxWidth >= 840.dp
                        if (compact) {
                            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                            val drawerScope = rememberCoroutineScope()
                            BackHandler(enabled = drawerState.isOpen) {
                                drawerScope.launch { drawerState.close() }
                            }
                            ModalNavigationDrawer(
                                drawerState = drawerState,
                                drawerContent = {
                                    ModalDrawerSheet(modifier = Modifier.widthIn(max = 320.dp)) {
                                        LibrarySidebar(books, favorites, labels, activeDownloadCount, section,
                                            onSelect = { destination ->
                                                if (destination == "downloading") tab = TAB_DOWNLOAD
                                                else section = destination
                                                drawerScope.launch { drawerState.close() }
                                            },
                                            onNewLabel = {
                                                drawerScope.launch {
                                                    drawerState.close()
                                                    labelDialogText = ""; labelDialogId = "new"
                                                }
                                            },
                                            onRenameLabel = { label ->
                                                drawerScope.launch {
                                                    drawerState.close()
                                                    labelDialogText = label.title; labelDialogId = label.id
                                                }
                                            },
                                            onDeleteLabel = { label ->
                                                drawerScope.launch {
                                                    drawerState.close()
                                                    deleteLabelId = label.id
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            ) {
                                BookshelfContent(model, books, favorites, labels, section,
                                    search, { search = it }, bookshelfSort, grid,
                                    coverWidth, fields, openImport,
                                    openBook = { activeBookId = it }, export = export,
                                    compact = true,
                                    onOpenCategories = { drawerScope.launch { drawerState.open() } },
                                    modifier = Modifier.fillMaxSize())
                            }
                        } else {
                            Row(Modifier.fillMaxSize()) {
                                LibrarySidebar(books, favorites, labels, activeDownloadCount, section,
                                    onSelect = { destination ->
                                        if (destination == "downloading") tab = TAB_DOWNLOAD
                                        else section = destination
                                    },
                                    onNewLabel = { labelDialogText = ""; labelDialogId = "new" },
                                    onRenameLabel = { label -> labelDialogText = label.title; labelDialogId = label.id },
                                    onDeleteLabel = { label -> deleteLabelId = label.id },
                                    modifier = Modifier.width(if (wideSidebar) 265.dp else 208.dp))
                                VerticalDivider()
                                BookshelfContent(model, books, favorites, labels, section,
                                    search, { search = it }, bookshelfSort, grid,
                                    coverWidth, fields, openImport,
                                    openBook = { activeBookId = it }, export = export,
                                    compact = false, onOpenCategories = {},
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp))
                            }
                        }
                    }
                }
                TAB_DOWNLOAD -> DownloadPage(tasks, model)
                TAB_SETTINGS -> SettingsPage(grid, bookshelfSort, fields, model, chooseStorage, requestFileAccess)
            }
        }
    }
}

/** Reused on phones and tablets; only the navigation chrome changes by width. */
@Composable
private fun BookshelfContent(
    model: OsrViewModel,
    books: List<ShelfBook>, favorites: Set<String>, labels: List<ShelfLabel>,
    section: String, search: String, onSearchChange: (String) -> Unit,
    bookshelfSort: BookshelfSort, grid: Boolean, coverWidth: Int, fields: Set<String>,
    openImport: () -> Unit, openBook: (String) -> Unit, export: (ShelfBook) -> Unit,
    compact: Boolean, onOpenCategories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sectionBooks = when {
        section == "recent" -> books.filter { it.lastOpened > 0L }
        section == "favorites" -> books.filter { it.id in favorites }
        section.startsWith("tag:") -> {
            val members = labels.firstOrNull { "tag:${it.id}" == section }?.bookIds.orEmpty()
            books.filter { it.id in members }
        }
        else -> books
    }
    val title = when (section) {
        "all" -> "本地书架"
        "recent" -> "最近阅读"
        "favorites" -> "我的最爱"
        else -> labels.firstOrNull { "tag:${it.id}" == section }?.title ?: "本地书架"
    }
    val filtered = sectionBooks.filter { search.isBlank() ||
        it.title.contains(search, true) || it.creator.contains(search, true) ||
            it.publisher.contains(search, true) || it.ssid.contains(search) }
    val sorted = remember(filtered, bookshelfSort) { BookshelfSorting.sorted(filtered, bookshelfSort) }
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                FilledTonalButton(onClick = onOpenCategories,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("分类")
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(title, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("${sectionBooks.size} 本", style = MaterialTheme.typography.bodySmall)
        }
        if (books.isNotEmpty()) {
            OutlinedTextField(value = search, onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                singleLine = true,
                placeholder = {
                    Text("搜索书名、作者、出版社", maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) })
        }
        if (sorted.isEmpty()) {
            if (books.isEmpty()) EmptyShelf(openImport)
            else Text("当前分类没有图书", Modifier.padding(20.dp))
        } else if (grid) {
            // The slider changes each cover's actual dp width immediately.
            // GridCells.Adaptive alone stretches its cells, which used to make
            // most slider positions look identical, particularly on phones.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val availableDp = maxWidth.value
                val count = BookshelfGridSizing.columns(availableDp, coverWidth)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(count),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sorted, key = { it.id }) { book ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            ShelfGridCard(model, book, fields,
                                open = { openBook(book.id) }, export = export,
                                modifier = Modifier.widthIn(max = coverWidth.dp).fillMaxWidth())
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sorted, key = { it.id }) { book ->
                    ShelfListRow(model, book, fields, coverWidth,
                        open = { openBook(book.id) }, export = export)
                }
            }
        }
    }
}

@Composable
private fun LibrarySidebar(books: List<ShelfBook>, favorites: Set<String>,
    labels: List<ShelfLabel>, activeDownloads: Int, current: String,
    onSelect: (String) -> Unit, onNewLabel: () -> Unit,
    onRenameLabel: (ShelfLabel) -> Unit, onDeleteLabel: (ShelfLabel) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(6.dp)) {
        DrawerCategory("all", "本地书架", books.size, Icons.Default.GridView, current, onSelect)
        DrawerCategory("recent", "最近阅读", books.count { it.lastOpened > 0L }, Icons.Default.History, current, onSelect)
        DrawerCategory("favorites", "我的最爱", books.count { it.id in favorites }, Icons.Default.Favorite, current, onSelect)
        DrawerCategory("downloading", "正在下载", activeDownloads, Icons.Default.Download, current, onSelect)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("我的标签", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onNewLabel) { Icon(Icons.Default.Add, "新建标签") }
        }
        labels.forEach { label ->
            var actionsVisible by remember(label.id) { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                SidebarCategoryRow(
                    title = label.title,
                    count = books.count { it.id in label.bookIds },
                    icon = Icons.Default.Label,
                    selected = current == "tag:${label.id}",
                    onClick = { onSelect("tag:${label.id}") },
                    onLongClick = { actionsVisible = true }
                )
                DropdownMenu(expanded = actionsVisible, onDismissRequest = { actionsVisible = false }) {
                    DropdownMenuItem(text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { actionsVisible = false; onRenameLabel(label) })
                    DropdownMenuItem(text = { Text("删除标签") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = { actionsVisible = false; onDeleteLabel(label) })
                }
            }
        }
        if (labels.isEmpty()) Text("点击 + 创建自己的标签", Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarCategoryRow(title: String, count: Int, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val activeColor = MaterialTheme.colorScheme.secondaryContainer
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .background(if (selected) activeColor else Color.Transparent, RoundedCornerShape(28.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f),
            maxLines = if (onLongClick != null) Int.MAX_VALUE else 1,
            overflow = if (onLongClick != null) TextOverflow.Clip else TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DrawerCategory(key: String, title: String, count: Int, icon: ImageVector,
    selected: String, onSelect: (String) -> Unit) {
    SidebarCategoryRow(title, count, icon, key == selected, onClick = { onSelect(key) })
}

@Composable
private fun EmptyShelf(openImport: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.LocalLibrary, null, Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(20.dp))
        Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
        Text("可以导入本地 PDZ 或 PDF 图书", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = openImport) { Text("选择文件") }
    }
}

@Composable
private fun ShelfCover(model: OsrViewModel, book: ShelfBook, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val storageRevision by model.storageRevision.collectAsState()
    BoxWithConstraints(modifier.background(Color(0xffe5e9f4), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center) {
        // Match *physical* display pixels, not dp: dense phones must not enlarge
        // an old 400px thumbnail to a blurry cover. A bounded oversampling margin
        // keeps the result sharp while avoiding full-page memory allocations.
        val targetWidthPx = (maxWidth.value * density.density * 1.35f)
            .toInt().coerceIn(800, 1600)
        val bitmap by produceState<Bitmap?>(initialValue = null,
            key1 = book, key2 = targetWidthPx, key3 = storageRevision) {
            value = withContext(Dispatchers.IO) { model.cover(book, targetWidthPx) }
        }
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), book.title,
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        else Icon(Icons.Default.AutoStories, null, Modifier.size(42.dp), tint = Color(0xff6984a7))
    }
}

@Composable
private fun BookOptions(book: ShelfBook, model: OsrViewModel, export: (ShelfBook) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var labelSubmenu by remember { mutableStateOf(false) }
    var removalPrompt by remember { mutableStateOf(false) }
    var deletePdz by remember { mutableStateOf(false) }
    var deletePdf by remember { mutableStateOf(false) }
    val favorites by model.favorites.collectAsState()
    val labels by model.labels.collectAsState()
    if (removalPrompt) AlertDialog(onDismissRequest = { removalPrompt = false },
        title = { Text("从书架移除？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("『${book.title}』将从书架和 bookshelf 索引中移除。默认保留本地文件。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deletePdz, onCheckedChange = { deletePdz = it },
                        enabled = book.publicPdzName != null)
                    Text("同时删除本地 PDZ", Modifier.clickable(enabled = book.publicPdzName != null) {
                        deletePdz = !deletePdz
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deletePdf, onCheckedChange = { deletePdf = it },
                        enabled = book.publicPdfName != null)
                    Text("同时删除本地 PDF", Modifier.clickable(enabled = book.publicPdfName != null) {
                        deletePdf = !deletePdf
                    })
                }
                if (deletePdz || deletePdf) Text("勾选的原文件将被永久删除，无法从书架恢复。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            removalPrompt = false
            model.removeFromShelf(book, deletePdz, deletePdf)
        }) { Text("确认移除") } },
        dismissButton = { TextButton(onClick = { removalPrompt = false }) { Text("取消") } })
    Box {
        IconButton(onClick = { expanded = true; labelSubmenu = false }) {
            Icon(Icons.Default.MoreVert, "图书操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false; labelSubmenu = false }) {
            if (labelSubmenu) {
                DropdownMenuItem(text = { Text("返回图书操作") },
                    onClick = { labelSubmenu = false },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) })
                if (labels.isEmpty()) DropdownMenuItem(text = { Text("尚未创建自定义标签") },
                    onClick = { labelSubmenu = false })
                labels.forEach { label ->
                    DropdownMenuItem(text = {
                        Text((if (book.id in label.bookIds) "✓  " else "") + label.title)
                    }, onClick = { expanded = false; labelSubmenu = false
                        model.toggleLabelBook(label.id, book.id) },
                        leadingIcon = { Icon(Icons.Default.Label, null) })
                }
            } else {
                DropdownMenuItem(text = { Text(if (book.id in favorites) "取消我的最爱" else "加入我的最爱") },
                    onClick = { expanded = false; model.toggleFavorite(book.id) },
                    leadingIcon = { Icon(Icons.Default.Favorite, null) })
                DropdownMenuItem(text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("加入标签", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }, onClick = { labelSubmenu = true },
                    leadingIcon = { Icon(Icons.Default.Label, null) })
                if (book.type == "PDZ") DropdownMenuItem(text = { Text("导出原始 PDZ") },
                    onClick = { expanded = false; export(book) },
                    leadingIcon = { Icon(Icons.Default.FileUpload, null) })
                if (book.type == "PDZ" && model.pdfFile(book) == null)
                    DropdownMenuItem(text = { Text("重试解码") }, onClick = {
                        expanded = false; model.predecode(book)
                    }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                DropdownMenuItem(text = { Text("从书架移除") }, onClick = {
                    expanded = false
                    deletePdz = false; deletePdf = false
                    removalPrompt = true
                }, leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) })
            }
        }
    }
}

@Composable
private fun BookFacts(book: ShelfBook, selected: Set<String>) {
    if ("贡献者" in selected && book.creator.isNotBlank()) Text(book.creator, style = MaterialTheme.typography.labelSmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if ("出版社" in selected && book.publisher.isNotBlank()) Text(book.publisher, style = MaterialTheme.typography.labelSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val facts = buildList {
        if ("年份" in selected && book.year != null) add("${book.year}")
        if ("页数" in selected && book.totalPages != null) add("${book.totalPages} 页")
    }
    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
    if ("进度" in selected) {
        val page = if (book.lastOpened == 0L) "未读" else if (book.totalPages != null)
            "${book.lastPage + 1}/${book.totalPages} 页" else "第 ${book.lastPage + 1} 页"
        Text(page, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    if (book.decodeMessage.isNotBlank())
        Text("暂未完成转码", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ShelfGridCard(model: OsrViewModel, book: ShelfBook, fields: Set<String>, open: () -> Unit,
    export: (ShelfBook) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = open).padding(4.dp)) {
        ShelfCover(model, book, Modifier.fillMaxWidth().aspectRatio(0.72f))
        Spacer(Modifier.height(6.dp))
        Text(book.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(book.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            BookOptions(book, model, export)
        }
        BookFacts(book, fields)
    }
}

@Composable
private fun ShelfListRow(model: OsrViewModel, book: ShelfBook, fields: Set<String>, coverWidth: Int,
    open: () -> Unit, export: (ShelfBook) -> Unit) {
    val thumbnailWidth = (coverWidth * 70f / 156f).toInt().coerceIn(50, 110)
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            ShelfCover(model, book, Modifier.size(width = thumbnailWidth.dp,
                height = (thumbnailWidth * 10 / 7).dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.type, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                BookFacts(book, fields)
            }
            BookOptions(book, model, export)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadPage(tasks: List<TransferTask>, model: OsrViewModel) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val deletable = tasks.filter { DownloadHistoryPolicy.canRemove(it.stage) }
    val validIds = deletable.map { it.id }.toSet()
    val selectedValid = selectedIds.intersect(validIds)
    if (showDeleteConfirmation) AlertDialog(
        onDismissRequest = { showDeleteConfirmation = false },
        title = { Text("删除下载记录？") },
        text = { Text("只删除选中的 ${selectedValid.size} 条下载记录，不会删除书架图书、PDZ 或 PDF。进行中的任务不可删除，请先取消。") },
        confirmButton = { TextButton(enabled = selectedValid.isNotEmpty(), onClick = {
            model.deleteDownloadRecords(selectedValid)
            selectedIds = emptySet()
            selectionMode = false
            showDeleteConfirmation = false
        }) { Text("删除记录") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") } })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("下载记录", style = MaterialTheme.typography.titleLarge)
        if (selectionMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = validIds.isNotEmpty() && selectedValid.size == validIds.size,
                    enabled = validIds.isNotEmpty(), onCheckedChange = { checked ->
                        selectedIds = if (checked) validIds else emptySet()
                    })
                Text("全选", modifier = Modifier.clickable { selectedIds = if (selectedValid.size == validIds.size) emptySet() else validIds })
                Spacer(Modifier.weight(1f))
                Text("${selectedValid.size} 已选", style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = selectedValid.isNotEmpty(), onClick = { showDeleteConfirmation = true }) {
                    Icon(Icons.Default.DeleteOutline, null)
                    Text("删除")
                }
                TextButton(onClick = { selectionMode = false; selectedIds = emptySet() }) { Text("取消") }
            }
        } else if (deletable.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { selectionMode = true }) { Text("选择记录") }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(tasks, key = { it.id }) { task ->
                val canRemove = DownloadHistoryPolicy.canRemove(task.stage)
                val checked = task.id in selectedValid
                Card(Modifier.fillMaxWidth().combinedClickable(
                    onLongClick = {
                        if (canRemove) { selectionMode = true; selectedIds = selectedIds + task.id }
                        else model.message("进行中的下载请先取消，结束后才能删除记录")
                    },
                    onClick = {
                        if (selectionMode && canRemove)
                            selectedIds = if (checked) selectedIds - task.id else selectedIds + task.id
                    })) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (selectionMode) Checkbox(checked = checked, enabled = canRemove,
                                onCheckedChange = { mark ->
                                    selectedIds = if (mark) selectedIds + task.id else selectedIds - task.id
                                })
                            Text(task.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                            if (!selectionMode && !canRemove)
                                TextButton(onClick = { model.cancelDownload(task.id) }) { Text("取消") }
                        }
                        Text(task.stage, style = MaterialTheme.typography.bodySmall,
                            color = if (task.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text("下载", style = MaterialTheme.typography.labelMedium)
                        if (task.downloadFraction == null && !canRemove)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { task.downloadFraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text(if (task.totalBytes != null) "${task.downloaded / 1024} / ${task.totalBytes / 1024} KiB"
                            else "${task.downloaded / 1024} KiB（总大小未知）", style = MaterialTheme.typography.labelSmall)
                        Text("解码 / PDF 缓存", style = MaterialTheme.typography.labelMedium)
                        when {
                            task.decodeFraction != null -> LinearProgressIndicator(progress = { task.decodeFraction!! },
                                modifier = Modifier.fillMaxWidth())
                            task.stage == "解码中" -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            else -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(if (task.totalPages != null) "${task.decodedPages} / ${task.totalPages} 页" else "尚未取得页数进度",
                            style = MaterialTheme.typography.labelSmall)
                        task.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(grid: Boolean, bookshelfSort: BookshelfSort, fields: Set<String>, model: OsrViewModel,
    chooseStorage: (PublicBookStorage.Kind) -> Unit, requestFileAccess: () -> Unit) {
    val rootAccess by model.rootStorageGranted.collectAsState()
    val readerLayout by model.readerLayout.collectAsState()
    val coverWidth by model.coverWidth.collectAsState()
    val storageRevision by model.storageRevision.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("阅读与书架", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("默认阅读模式", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ReaderLayout.SINGLE, ReaderLayout.SPREAD, ReaderLayout.CONTINUOUS).forEach { layout ->
                ReaderRadioChoice(layout.label, selected = readerLayout == layout) {
                    model.setReaderLayout(layout)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("网格封面视图", modifier = Modifier.weight(1f))
            Switch(grid, onCheckedChange = model::setGrid)
        }
        var sortMenuExpanded by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("书架图书排序", modifier = Modifier.weight(1f))
            Box {
                FilledTonalButton(onClick = { sortMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(bookshelfSort.concise, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, "选择书架排序方式",
                        modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }) {
                    BookshelfSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            leadingIcon = {
                                RadioButton(selected = option == bookshelfSort,
                                    onClick = null)
                            },
                            onClick = {
                                model.setSort(option)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
        Text("封面大小：${coverWidth} dp", style = MaterialTheme.typography.bodyMedium)
        Slider(value = coverWidth.toFloat(), onValueChange = { model.setCoverWidth(it.toInt()) },
            valueRange = 112f..240f)
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("书架显示哪些信息", style = MaterialTheme.typography.titleMedium)
        Text("书名始终显示，其他信息可自由选择。", style = MaterialTheme.typography.bodySmall)
        listOf("贡献者", "出版社", "年份", "页数", "进度").forEach { field ->
            Row(Modifier.fillMaxWidth().clickable { model.showField(field, field !in fields) },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = field in fields, onCheckedChange = { model.showField(field, it) })
                Text(field)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("文件存储", style = MaterialTheme.typography.titleMedium)
        Text("默认保存到内部共享存储根目录 /OpenSSReader/pdz、pdf 和 bookshelf。" +
            "这需要 Android『所有文件访问』授权；应用内部仍保留用于阅读的工作副本。",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        if (Build.VERSION.SDK_INT >= 30) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (rootAccess) "已允许管理所有文件" else "尚未授权根目录文件访问",
                    modifier = Modifier.weight(1f),
                    color = if (rootAccess) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = requestFileAccess) { Text("权限设置") }
            }
        }
        listOf(PublicBookStorage.Kind.PDZ, PublicBookStorage.Kind.PDF,
            PublicBookStorage.Kind.BOOKSHELF).forEach { kind ->
            // storageRevision makes the labels recompose after a folder change.
            val display = remember(storageRevision, kind) { model.storageLabel(kind) }
            Spacer(Modifier.height(8.dp))
            Text("${kind.label} 保存位置", style = MaterialTheme.typography.titleSmall)
            Text(display, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chooseStorage(kind) }) { Text("选择文件夹") }
                if (model.isCustomStorage(kind))
                    TextButton(onClick = { model.resetStorage(kind) }) { Text("恢复默认") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { model.rescanBookshelf() }) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(6.dp))
            Text("重新扫描书架")
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        ReleaseUpdateSettings()
        Spacer(Modifier.height(16.dp))
        AboutAppSettings()

    }
}

@Composable
private fun ReaderOrInfo(model: OsrViewModel, book: ShelfBook, onBack: () -> Unit,
    export: (ShelfBook) -> Unit) {
    val pdf = model.pdfFile(book)
    if (pdf == null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Spacer(Modifier.height(12.dp))
            Text(book.title, style = MaterialTheme.typography.headlineSmall)
            Text("${book.creator}\n${book.publisher} ${book.year ?: ""}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("原始 PDZ 已安全保存，但当前 A1 尚未完成这本书的页面解码。", color = MaterialTheme.colorScheme.error)
            if (book.decodeMessage.isNotBlank()) Text(book.decodeMessage)
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(onClick = { model.predecode(book) }) { Text("重试解码") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { export(book) }) { Text("导出原始 PDZ") }
            }
            val toc by produceState<List<org.openssreader.android.core.Chapter>?>(initialValue = null, key1 = book.id) {
                value = withContext(Dispatchers.IO) { model.chapters(book) }
            }
            val availableToc = toc
            if (!availableToc.isNullOrEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("真实章节目录（${availableToc.size} 条）", style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(availableToc) { chapter ->
                        Text("　".repeat(min(4, (chapter.level - 1).coerceAtLeast(0))) + chapter.title + "  · 正文 ${chapter.bodyPage}",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp))
                    }
                }
            }
        }
        return
    }
    ReaderPage(model, book, pdf, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPage(model: OsrViewModel, book: ShelfBook, pdf: File, onBack: () -> Unit) {
    val maxPage = (book.totalPages ?: 1).coerceAtLeast(1)
    var page by rememberSaveable(book.id) { mutableIntStateOf(book.lastPage.coerceIn(0, maxPage - 1)) }
    var showToc by rememberSaveable { mutableStateOf(false) }
    var showJump by rememberSaveable { mutableStateOf(false) }
    var jumpValue by rememberSaveable { mutableStateOf("") }
    var jumpRequest by remember { mutableIntStateOf(0) }
    var showBars by rememberSaveable { mutableStateOf(true) }
    val applicationContext = LocalContext.current
    val enhancementStore = remember(applicationContext) { ReaderEnhancementStore(applicationContext) }
    var enhancement by remember(book.id) { mutableStateOf(enhancementStore.read(book.id)) }
    fun saveEnhancement(settings: ReaderEnhanceSettings) {
        enhancement = settings.normalized()
        enhancementStore.write(book.id, enhancement)
    }
    var enhancementSheet by rememberSaveable { mutableStateOf(false) }
    var previewOriginal by remember { mutableStateOf(false) }
    var detectingReference by remember(book.id) { mutableStateOf(false) }
    var referenceMessage by remember(book.id) { mutableStateOf("") }
    val referenceScope = rememberCoroutineScope()
    fun generateReferenceCrop(index: Int, second: Boolean = false) {
        if (detectingReference) return
        if (index !in 0 until maxPage) {
            referenceMessage = "页码无效：请输入 1–$maxPage"
            return
        }
        val currentPrimary = enhancement.referenceCrop
        if (second && (currentPrimary == null || ((index - currentPrimary.pageIndex) and 1) == 0)) {
            referenceMessage = "第二基准页必须与首个基准页处于相反的左右页序列。"
            return
        }
        detectingReference = true
        referenceMessage = "正在分析第 ${index + 1} 页的内容边界…"
        referenceScope.launch {
            try {
                val margin = enhancement.safeMargin
                suspend fun analyze(candidate: Int): ReaderReferenceCrop? {
                    val bitmap = withContext(Dispatchers.IO) { renderPdfPage(pdf, candidate, 1600) }
                        ?: return null
                    return withContext(Dispatchers.Default) {
                        val job = currentCoroutineContext()
                        ReaderBitmapEnhancer.detectReferenceCrop(bitmap, candidate, margin) {
                            job.ensureActive()
                        }
                    }
                }
                val fixed = analyze(index)
                if (fixed == null) {
                    referenceMessage = "第 ${index + 1} 页无法可靠检测裁边；原设置未改变，请选普通正文页。"
                } else if (second) {
                    saveEnhancement(enhancement.copy(
                        cropMode = ReaderCropMode.REFERENCE,
                        secondReferenceCrop = fixed,
                        recommendSecondReference = false,
                        enabled = true))
                    referenceMessage = "已补充第 ${index + 1} 页作为另一侧基准；左右页现在使用两套独立裁边参数。"
                } else {
                    // Compare one opposite-side neighbor. Do not silently impose a second
                    // template: offer a second selection only if the difference is material.
                    val adjacent = if (index + 1 < maxPage) index + 1 else index - 1
                    val opposite = if (adjacent in 0 until maxPage) try {
                        analyze(adjacent)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } else null
                    val recommend = opposite != null &&
                        ReaderImageEnhancer.materiallyDifferentOppositePage(fixed, opposite)
                    saveEnhancement(enhancement.copy(
                        cropMode = ReaderCropMode.REFERENCE,
                        referenceCrop = fixed,
                        secondReferenceCrop = null,
                        recommendSecondReference = recommend,
                        enabled = true))
                    referenceMessage = if (recommend)
                        "第 ${index + 1} 页已设为基准。检测到左右页边距差异，建议再补选另一页作为第二基准页。"
                    else "第 ${index + 1} 页已设为基准，另一侧页面按左右镜像裁边。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                referenceMessage = "基准页裁边分析失败，已保留原设置。"
            } finally {
                detectingReference = false
            }
        }
    }
    val readerLayout by model.readerLayout.collectAsState()
    val continuous = readerLayout == ReaderLayout.CONTINUOUS
    val spread = readerLayout == ReaderLayout.SPREAD
    var scale by remember(book.id) { mutableFloatStateOf(1f) }
    var panX by remember(book.id) { mutableFloatStateOf(0f) }
    var panY by remember(book.id) { mutableFloatStateOf(0f) }
    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = page)
    val readerScope = rememberCoroutineScope()
    val chapters by produceState<List<org.openssreader.android.core.Chapter>?>(initialValue = null, key1 = book.id) {
        value = withContext(Dispatchers.IO) { model.chapters(book) }
    }
    val sections by produceState<List<BookSection>>(initialValue = emptyList(), key1 = book.id) {
        value = withContext(Dispatchers.IO) { model.sections(book) }
    }
    var sectionMenu by remember { mutableStateOf(false) }
    val validSections = remember(sections, maxPage) { sections.filter { it.zeroBasedPage in 0 until maxPage } }
    val currentSectionTitle = remember(validSections, page, spread, maxPage) {
        if (validSections.isEmpty()) "章节" else {
            val anchorPage = if (spread) ReaderSpread.anchor(page, maxPage) else page
            validSections.lastOrNull { it.zeroBasedPage <= anchorPage }?.title ?: validSections.first().title
        }
    }
    // Align the requested page to a stable two-page spread. A book's cover
    // (page 1) is displayed alone, then pages 2–3, 4–5, etc.
    LaunchedEffect(readerLayout) {
        if (spread) page = ReaderSpread.anchor(page, maxPage)
        scale = 1f; panX = 0f; panY = 0f
    }
    val firstPage = if (spread) ReaderSpread.anchor(page, maxPage) else page
    val secondPage = if (spread) ReaderSpread.indices(firstPage, maxPage).getOrNull(1) else null
    val rendered by produceState<Pair<Boolean, Bitmap?>>(initialValue = Pair(true, null),
        key1 = pdf.absolutePath, key2 = firstPage) {
        value = Pair(false, withContext(Dispatchers.IO) { renderPdfPage(pdf, firstPage, 2100) })
    }
    val renderedRight by produceState<Pair<Boolean, Bitmap?>>(initialValue = Pair(true, null),
        key1 = pdf.absolutePath, key2 = secondPage) {
        value = if (secondPage == null) Pair(false, null)
                else Pair(false, withContext(Dispatchers.IO) { renderPdfPage(pdf, secondPage, 2100) })
    }
    val effectiveEnhancement = if (previewOriginal) enhancement.copy(enabled = false)
        else enhancement
    // Keep raw PDF rendering keyed only by page, so changing enhancement settings
    // never re-decodes PDZ or re-renders the PDF and remains instant to revert.
    val enhancedLeft by produceState<Pair<ReaderEnhanceSettings?, Bitmap?>>(initialValue = null to null,
        key1 = rendered.second, key2 = effectiveEnhancement) {
        value = null to null
        val original = rendered.second
        if (original != null) {
            val result = if (!effectiveEnhancement.enabled || !effectiveEnhancement.requiresProcessing()) original
                else withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    try { ReaderBitmapEnhancer.process(original, effectiveEnhancement, firstPage) { context.ensureActive() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { original }
                }
            value = effectiveEnhancement to result
        }
    }
    val enhancedRight by produceState<Pair<ReaderEnhanceSettings?, Bitmap?>>(initialValue = null to null,
        key1 = renderedRight.second, key2 = effectiveEnhancement) {
        value = null to null
        val original = renderedRight.second
        if (original != null) {
            val result = if (!effectiveEnhancement.enabled || !effectiveEnhancement.requiresProcessing()) original
                else withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    try { ReaderBitmapEnhancer.process(original, effectiveEnhancement, secondPage ?: firstPage) { context.ensureActive() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { original }
                }
            value = effectiveEnhancement to result
        }
    }
    // Use the raw page while enhancement is being calculated, never stale results.
    val shownLeft = if (enhancedLeft.first == effectiveEnhancement)
        enhancedLeft.second ?: rendered.second else rendered.second
    val shownRight = if (enhancedRight.first == effectiveEnhancement)
        enhancedRight.second ?: renderedRight.second else renderedRight.second
    LaunchedEffect(page, readerLayout) { if (!continuous) model.savePage(book, firstPage) }
    LaunchedEffect(continuous, book.id) {
        if (continuous) {
            scrollState.scrollToItem(page)
            snapshotFlow { scrollState.firstVisibleItemIndex }
            .distinctUntilChanged().collect { visible ->
                if (visible in 0 until maxPage) {
                    page = visible
                    model.savePage(book, visible)
                }
            }
        }
    }
    var viewportWidth by remember { mutableIntStateOf(1) }
    var viewportHeight by remember { mutableIntStateOf(1) }
    fun clampPan() {
        val maxX = (viewportWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (viewportHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
    Scaffold(topBar = {
        if (showBars) Column(Modifier.fillMaxWidth()) {
            TopAppBar(title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                } })
            // Compact radio controls, horizontally scrollable on narrow phones;
            // on a tablet the complete row fits naturally beside the layout modes.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(ReaderLayout.SINGLE, ReaderLayout.SPREAD, ReaderLayout.CONTINUOUS).forEach { layout ->
                    ReaderRadioChoice(layout.label, selected = readerLayout == layout) {
                        model.setReaderLayout(layout)
                    }
                }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 5.dp))
                ReaderRadioChoice("原始图像", selected = !enhancement.enabled || previewOriginal) {
                    previewOriginal = false
                    saveEnhancement(enhancement.copy(enabled = false))
                }
                ReaderRadioChoice("扫描增强", selected = enhancement.enabled && !previewOriginal) {
                    previewOriginal = false
                    val selected = if (!enhancement.requiresProcessing())
                        enhancement.copy(mode = ReaderEnhanceMode.CLEAN, enabled = true)
                    else enhancement.copy(enabled = true)
                    saveEnhancement(selected)
                }
                IconButton(onClick = { previewOriginal = false; enhancementSheet = true }) {
                    Icon(Icons.Default.Settings, "扫描增强设置")
                }
            }
        }
    }, bottomBar = {
        if (showBars) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            if (!continuous && maxPage > 1) Slider(value = page.toFloat(), onValueChange = {
                val target = it.toInt().coerceIn(0, maxPage - 1)
                page = if (spread) ReaderSpread.anchor(target, maxPage) else target
                scale = 1f; panX = 0f; panY = 0f
            }, valueRange = 0f..(maxPage - 1).toFloat(), modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val previous = if (spread) ReaderSpread.previous(page, maxPage) else (page - 1).coerceAtLeast(0)
                val next = if (spread) ReaderSpread.next(page, maxPage) else (page + 1).coerceAtMost(maxPage - 1)
                if (!chapters.isNullOrEmpty()) IconButton(onClick = { showToc = !showToc }) {
                    Icon(Icons.Default.List, "显示或隐藏章节目录")
                }
                IconButton(enabled = previous != page, onClick = {
                    page = previous; jumpRequest++; scale = 1f; panX = 0f; panY = 0f
                }) { Icon(Icons.Default.ChevronLeft, "上一页") }
                TextButton(onClick = { jumpValue = (page + 1).toString(); showJump = true }) {
                    Text(
                        if (spread) ReaderSpread.label(page, maxPage) else "${page + 1} / $maxPage",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                IconButton(enabled = next != page, onClick = {
                    page = next; jumpRequest++; scale = 1f; panX = 0f; panY = 0f
                }) { Icon(Icons.Default.ChevronRight, "下一页") }
                if (validSections.isNotEmpty()) Box {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 1.dp,
                        modifier = Modifier.clickable { sectionMenu = true }
                    ) {
                        // Center the section title within the whole control, not merely
                        // within the area left over after the dropdown arrow.
                        Box(
                            modifier = Modifier.width(96.dp).height(38.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                currentSectionTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "前言／目录／正文跳转",
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .padding(end = 3.dp).size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = sectionMenu,
                        onDismissRequest = { sectionMenu = false },
                        modifier = Modifier.width(112.dp)
                    ) {
                        validSections.forEach { section ->
                            DropdownMenuItem(
                                text = {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(
                                            section.title,
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                },
                                onClick = {
                                    page = if (spread) ReaderSpread.anchor(section.zeroBasedPage, maxPage)
                                           else section.zeroBasedPage
                                    jumpRequest++
                                    scale = 1f; panX = 0f; panY = 0f
                                    sectionMenu = false
                                },
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }) { inner ->
        if (continuous) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(inner).background(Color(0xffe9ecf2)),
                state = scrollState, verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                items(maxPage) { index ->
                    ContinuousPdfPage(pdf, index, onTap = { tapX, pageWidth ->
                        val zone = ReaderTapNavigation.zone(tapX, pageWidth)
                        if (zone == ReaderTapZone.CENTER) {
                            showBars = !showBars
                        } else {
                            val destination = ReaderTapNavigation.destination(index, maxPage, false, zone)
                            if (destination != index) readerScope.launch {
                                scrollState.animateScrollToItem(destination)
                            }
                        }
                    }, enhancement = enhancement, showOriginal = previewOriginal)
                }
            }
        } else {
            // Page navigation is by short taps in the left/right 28% only.
            // There is deliberately NO single-finger swipe-to-turn branch.
            // Multi-touch pinch and pan (including one-finger pan when zoomed)
            // remain intact. A moving/long/zoomed gesture never turns a page.
            val touch = Modifier.pointerInput(firstPage, maxPage, readerLayout) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val beganZoomed = scale > 1.01f
                    var multiplePointers = false
                    var travel = 0f
                    var eventTime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent()
                        eventTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: eventTime
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || event.changes.size >= 2) multiplePointers = true
                        val delta = event.calculatePan()
                        val pinch = event.calculateZoom()
                        if (multiplePointers || scale > 1.01f) {
                            scale = (scale * pinch).coerceIn(1f, 6f)
                            panX += delta.x
                            panY += delta.y
                            if (scale <= 1.01f) {
                                scale = 1f; panX = 0f; panY = 0f
                            } else clampPan()
                            event.changes.forEach { if (it.pressed) it.consume() }
                        } else {
                            // Count path travel, not just start/end distance;
                            // dragging out and back must not be mistaken for a tap.
                            travel += abs(delta.x) + abs(delta.y)
                        }
                    } while (event.changes.any { it.pressed })
                    if (!multiplePointers && !beganZoomed && scale <= 1.01f &&
                        travel <= viewConfiguration.touchSlop &&
                        eventTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        val zone = ReaderTapNavigation.zone(down.position.x, size.width.toFloat())
                        if (zone == ReaderTapZone.CENTER) {
                            showBars = !showBars
                        } else {
                            val destination = ReaderTapNavigation.destination(page, maxPage, spread, zone)
                            if (destination != page) {
                                page = destination
                                scale = 1f; panX = 0f; panY = 0f
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(inner).background(Color(0xffe9ecf2))
                .onSizeChanged { viewportWidth = it.width; viewportHeight = it.height; clampPan() }
                .clipToBounds().then(touch),
                contentAlignment = Alignment.Center) {
                if (rendered.first || (spread && renderedRight.first)) CircularProgressIndicator()
                else if (rendered.second == null || (secondPage != null && renderedRight.second == null))
                    Text("该 PDF 页面无法渲染", color = MaterialTheme.colorScheme.error)
                else if (spread && secondPage != null) {
                    // Fit the ACTUAL page widths, not two equal slots. Equal slots
                    // create huge center margins for narrow scanned pages.
                    // The effective gap shrinks as the entire spread is zoomed.
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val first = shownLeft!!
                        val second = shownRight!!
                        val ratio1 = first.width.toFloat() / first.height
                        val ratio2 = second.width.toFloat() / second.height
                        val gap = 3.dp / scale
                        val sheetHeight = minOf(maxHeight, (maxWidth - gap) / (ratio1 + ratio2))
                        val sheetWidth1 = sheetHeight * ratio1
                        val sheetWidth2 = sheetHeight * ratio2
                        Row(Modifier.width(sheetWidth1 + sheetWidth2 + gap).height(sheetHeight)
                            .graphicsLayer(scaleX = scale, scaleY = scale,
                                translationX = panX, translationY = panY),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalAlignment = Alignment.CenterVertically) {
                            Image(first.asImageBitmap(), "第 ${firstPage + 1} 页",
                                Modifier.width(sheetWidth1).fillMaxHeight(), contentScale = ContentScale.FillBounds)
                            Image(second.asImageBitmap(), "第 ${secondPage + 1} 页",
                                Modifier.width(sheetWidth2).fillMaxHeight(), contentScale = ContentScale.FillBounds)
                        }
                    }
                } else Image(shownLeft!!.asImageBitmap(), "第 ${firstPage + 1} 页", Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY),
                    contentScale = ContentScale.Fit)
            }
        }
    }
    if (enhancementSheet) ReaderEnhancementSheet(
        settings = enhancement,
        onChange = ::saveEnhancement,
        currentPage = firstPage,
        maxPage = maxPage,
        detectingReference = detectingReference,
        referenceMessage = referenceMessage,
        onGenerateReference = { index, second -> generateReferenceCrop(index, second) },
        onDismiss = { enhancementSheet = false },
        previewOriginal = previewOriginal,
        onPreviewOriginal = {
            previewOriginal = it
            if (it) enhancementSheet = false // expose the full original page for comparison
        }
    )
    val availableChapters = chapters
    if (showToc && !availableChapters.isNullOrEmpty()) AlertDialog(onDismissRequest = { showToc = false },
        title = { Text("真实章节目录") }, text = {
            LazyColumn(Modifier.heightIn(max = 470.dp)) {
                items(availableChapters) { chapter ->
                    Text("　".repeat(min(4, (chapter.level - 1).coerceAtLeast(0))) + chapter.title,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val destination = chapter.documentPageZeroBased
                            if (destination != null && destination in 0 until maxPage) {
                                page = if (spread) ReaderSpread.anchor(destination, maxPage) else destination
                                jumpRequest++
                            } else model.message("该章节尚无可靠的阅读页码映射")
                            showToc = false
                        }.padding(vertical = 10.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { showToc = false }) { Text("关闭") } })
    if (showJump) AlertDialog(onDismissRequest = { showJump = false }, title = { Text("跳转到页面") }, text = {
        OutlinedTextField(jumpValue, onValueChange = { jumpValue = it.filter(Char::isDigit).take(7) },
            label = { Text("输入 1–$maxPage") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }, confirmButton = { TextButton(onClick = {
        jumpValue.toIntOrNull()?.let { if (it in 1..maxPage) {
            page = if (spread) ReaderSpread.anchor(it - 1, maxPage) else it - 1
            jumpRequest++; scale = 1f; panX = 0f; panY = 0f
        } }
        showJump = false
    }) { Text("跳转") } }, dismissButton = { TextButton(onClick = { showJump = false }) { Text("取消") } })
    // Reflect explicit chapter and number jumps in scroll mode, without
    // triggering a continuous recomposition-driven scroll loop.
    LaunchedEffect(continuous, jumpRequest) {
        if (continuous && jumpRequest > 0 && page != scrollState.firstVisibleItemIndex)
            scrollState.scrollToItem(page)
    }
}

@Composable
private fun ContinuousPdfPage(pdf: File, index: Int, onTap: (Float, Float) -> Unit,
    enhancement: ReaderEnhanceSettings, showOriginal: Boolean) {
    val rawBitmap by produceState<Bitmap?>(initialValue = null, key1 = pdf.absolutePath, key2 = index) {
        value = withContext(Dispatchers.IO) { renderPdfPage(pdf, index, 1550) }
    }
    val enhancedBitmap by produceState<Pair<ReaderEnhanceSettings?, Bitmap?>>(initialValue = null to null,
        key1 = rawBitmap, key2 = enhancement, key3 = showOriginal) {
        value = null to null
        if (rawBitmap != null) {
            val image = if (showOriginal || !enhancement.enabled || !enhancement.requiresProcessing())
                rawBitmap else withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    try { ReaderBitmapEnhancer.process(rawBitmap!!, enhancement, index) { context.ensureActive() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { rawBitmap }
                }
            value = enhancement to image
        }
    }
    val bitmap = if (!showOriginal && enhancement.enabled && enhancedBitmap.first == enhancement)
        enhancedBitmap.second ?: rawBitmap else rawBitmap
    var zoom by remember(pdf.absolutePath, index) { mutableFloatStateOf(1f) }
    var offsetX by remember(pdf.absolutePath, index) { mutableFloatStateOf(0f) }
    var offsetY by remember(pdf.absolutePath, index) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var height by remember { mutableIntStateOf(1) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentZoom by rememberUpdatedState(zoom)
    val transform = rememberTransformableState { factor, pan, _ ->
        zoom = (zoom * factor).coerceIn(1f, 6f)
        if (zoom <= 1.01f) {
            zoom = 1f; offsetX = 0f; offsetY = 0f
        } else {
            val boundX = (width * (zoom - 1f) / 2f).coerceAtLeast(0f)
            val boundY = (height * (zoom - 1f) / 2f).coerceAtLeast(0f)
            offsetX = (offsetX + pan.x).coerceIn(-boundX, boundX)
            offsetY = (offsetY + pan.y).coerceIn(-boundY, boundY)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap == null) Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else Box(Modifier.fillMaxWidth()
            .aspectRatio(bitmap!!.width.toFloat() / bitmap!!.height)
            .onSizeChanged { width = it.width; height = it.height }
            .clipToBounds()
            // One-finger vertical scrolling belongs to LazyColumn at 1x.
            // Two-finger pinch zoom and zoomed panning belong to this page.
            .transformable(state = transform, canPan = { zoom > 1.01f })
             .pointerInput(index) {
                detectTapGestures(onTap = { point ->
                    if (currentZoom <= 1.01f) currentOnTap(point.x, width.toFloat())
                })
            }) {
            Image(bitmap!!.asImageBitmap(), "第 ${index + 1} 页", Modifier.fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom,
                    translationX = offsetX, translationY = offsetY),
                contentScale = ContentScale.FillBounds)
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A single clear dot selection, never a chip/highlight-only pseudo selection. */
@Composable
private fun ReaderRadioChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(34.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderEnhancementSheet(
    settings: ReaderEnhanceSettings,
    onChange: (ReaderEnhanceSettings) -> Unit,
    currentPage: Int,
    maxPage: Int,
    detectingReference: Boolean,
    referenceMessage: String,
    onGenerateReference: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
    previewOriginal: Boolean,
    onPreviewOriginal: (Boolean) -> Unit
) {
    var strengthDraft by remember(settings.mode, settings.strength) {
        mutableFloatStateOf(settings.strength.toFloat())
    }
    var marginDraft by remember(settings.safeMargin) {
        mutableFloatStateOf(settings.safeMargin.toFloat())
    }
    var pageInput by remember { mutableStateOf("") }
    var requestingSecond by remember { mutableStateOf(false) }
    var showPageInput by remember { mutableStateOf(false) }
    val typed = pageInput.toIntOrNull()
    val validTyped = typed != null && typed in 1..maxPage &&
        (!requestingSecond || (settings.referenceCrop != null &&
            ((typed - 1 - settings.referenceCrop.pageIndex) and 1) != 0))
    val primary = settings.referenceCrop
    val suggestedOpposite = primary?.let {
        if (it.pageIndex + 1 < maxPage) it.pageIndex + 1
        else if (it.pageIndex > 0) it.pageIndex - 1 else null
    }
    ModalBottomSheet(onDismissRequest = { onPreviewOriginal(false); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("扫描增强设置", style = MaterialTheme.typography.titleLarge)
            Text("设置只影响阅读显示，不修改 PDZ 或 PDF。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("增强模式", style = MaterialTheme.typography.titleMedium)
            // Keep the four options as an uncluttered single-select group.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(ReaderEnhanceMode.CLEAN, ReaderEnhanceMode.GRAYSCALE,
                    ReaderEnhanceMode.BLACK_WHITE, ReaderEnhanceMode.COLOR).forEach { mode ->
                    ReaderRadioChoice(when(mode) {
                        ReaderEnhanceMode.CLEAN -> "保真去灰底"
                        ReaderEnhanceMode.GRAYSCALE -> "灰度增强"
                        ReaderEnhanceMode.BLACK_WHITE -> "强黑白"
                        ReaderEnhanceMode.COLOR -> "彩色保真"
                        ReaderEnhanceMode.ORIGINAL -> "原始"
                    }, settings.mode == mode) {
                        onChange(settings.copy(mode = mode, enabled = true))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("增强强度", style = MaterialTheme.typography.bodyMedium)
                Text("${strengthDraft.toInt()}%")
            }
            Slider(value = strengthDraft, onValueChange = { strengthDraft = it },
                onValueChangeFinished = {
                    onChange(settings.copy(strength = strengthDraft.toInt()))
                }, valueRange = 0f..100f, steps = 19)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("裁边方式", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                ReaderRadioChoice("不裁边", settings.cropMode == ReaderCropMode.OFF) {
                    onChange(settings.copy(cropMode = ReaderCropMode.OFF))
                }
                ReaderRadioChoice("逐页自动", settings.cropMode == ReaderCropMode.AUTO) {
                    onChange(settings.copy(cropMode = ReaderCropMode.AUTO, enabled = true))
                }
                ReaderRadioChoice("基准页固定", settings.cropMode == ReaderCropMode.REFERENCE) {
                    if (settings.referenceCrop != null) {
                        onChange(settings.copy(cropMode = ReaderCropMode.REFERENCE, enabled = true))
                    } else {
                        // No reference yet: open its compact chooser without forcing a
                        // nonexistent crop mode into persisted settings.
                        requestingSecond = false
                        showPageInput = false
                        onGenerateReference(currentPage, false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("保留边距", style = MaterialTheme.typography.bodyMedium)
                Text("${marginDraft.toInt()}%")
            }
            Slider(value = marginDraft, onValueChange = { marginDraft = it },
                onValueChangeFinished = { onChange(settings.copy(safeMargin = marginDraft.toInt())) },
                valueRange = 2f..12f, steps = 9)
            if (settings.cropMode == ReaderCropMode.REFERENCE || primary != null) {
                Spacer(Modifier.height(5.dp))
                Text("固定裁边", style = MaterialTheme.typography.titleSmall)
                if (primary != null) {
                    Text("主基准：第 ${primary.pageIndex + 1} 页" +
                        (if (settings.secondReferenceCrop != null)
                            " · 第二基准：第 ${settings.secondReferenceCrop.pageIndex + 1} 页" else " · 另一侧自动镜像"),
                        style = MaterialTheme.typography.bodySmall)
                    Text("更改保留边距后，需重新生成基准才会更新裁边框。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { requestingSecond = false; showPageInput = false;
                        onGenerateReference(currentPage, false) }, enabled = !detectingReference) {
                        Text("使用当前页")
                    }
                    TextButton(onClick = { requestingSecond = false; showPageInput = true; pageInput = "" },
                        enabled = !detectingReference) { Text("输入页码") }
                }
                if (primary != null && settings.secondReferenceCrop == null &&
                    settings.recommendSecondReference) {
                    Text("检测到左右页边距差异，建议再补选另一页作为第二基准页。",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (primary != null && settings.secondReferenceCrop == null) {
                    // Usually hidden; a single recommended action appears on mismatch.
                    TextButton(onClick = {
                        requestingSecond = true
                        showPageInput = false
                        if (suggestedOpposite != null) onGenerateReference(suggestedOpposite, true)
                    }, enabled = !detectingReference && suggestedOpposite != null) {
                        Text(if (settings.recommendSecondReference) "补选第 ${suggestedOpposite?.plus(1)} 页作为第二基准"
                            else "按需添加第二基准页")
                    }
                    if (settings.recommendSecondReference) {
                        TextButton(onClick = { requestingSecond = true; showPageInput = true; pageInput = "" },
                            enabled = !detectingReference) { Text("指定第二基准页码") }
                    }
                } else if (settings.secondReferenceCrop != null) {
                    TextButton(onClick = { requestingSecond = true; showPageInput = true; pageInput = "" },
                        enabled = !detectingReference) { Text("更换第二基准页") }
                }
                if (showPageInput) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = pageInput,
                            onValueChange = { pageInput = it.filter(Char::isDigit).take(7) },
                            label = { Text(if (requestingSecond) "第二基准页码" else "基准页码") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = pageInput.isNotEmpty() && !validTyped,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (typed != null) onGenerateReference(typed - 1, requestingSecond)
                            showPageInput = false
                        }, enabled = validTyped && !detectingReference) { Text("确定") }
                    }
                }
            } else {
                // No crop chosen: allow a base to be generated without a modal detour.
                TextButton(onClick = { onGenerateReference(currentPage, false) },
                    enabled = !detectingReference) { Text("使用当前页生成固定裁边") }
                TextButton(onClick = { requestingSecond = false; showPageInput = true; pageInput = "" },
                    enabled = !detectingReference) { Text("指定基准页码") }
                if (showPageInput) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = pageInput,
                            onValueChange = { pageInput = it.filter(Char::isDigit).take(7) },
                            label = { Text("基准页码") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Button(onClick = {
                            if (typed != null) onGenerateReference(typed - 1, false)
                            showPageInput = false
                        }, enabled = validTyped && !detectingReference) { Text("生成") }
                    }
                }
            }
            if (detectingReference) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (referenceMessage.isNotBlank()) Text(referenceMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPreviewOriginal(true) }) { Text("对比原图") }
                TextButton(onClick = { onChange(ReaderEnhanceSettings()) }) { Text("恢复默认") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onPreviewOriginal(false); onDismiss() }) { Text("完成") }
            }
            Text(if (previewOriginal) "当前临时显示原图" else
                "左右页按 PDF 内部页序交替，基准数据按书籍独立保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun renderPdfPage(file: File, page: Int, maxDimension: Int): Bitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (page !in 0 until renderer.pageCount) null else renderer.openPage(page).use { p ->
                val sample = min(maxDimension.toFloat() / p.width, maxDimension.toFloat() / p.height)
                    .coerceAtMost(2f)
                val width = (p.width * sample).toInt().coerceAtLeast(1)
                val height = (p.height * sample).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
} catch (_: Exception) { null }
