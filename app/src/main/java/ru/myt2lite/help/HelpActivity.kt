package ru.myt2lite.help

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.myt2lite.chat.ChatActivity
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme

/**
 * Раздел «Вопросы и ответы»: поиск ответа, темы, статьи.
 * Список тем и статей — с api.t2.ru (HelpRepository), сама статья — страница сайта t2 внутри приложения.
 */
class HelpActivity : ComponentActivity() {

    private val vm: HelpViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HelpRoot(vm, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, HelpActivity::class.java))
    }
}

@Composable
private fun HelpRoot(vm: HelpViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    BackHandler(enabled = state.article != null || state.categoryId != null) { vm.back() }

    val article = state.article
    val category = state.category
    when {
        article != null -> ArticleScreen(article, onBack = { vm.back() })
        category != null -> CategoryScreen(category, onBack = { vm.back() }, onArticle = vm::openArticle)
        else -> MainScreen(vm, state, onBack = onClose)
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        action?.invoke()
    }
    HorizontalDivider()
}

// ---------------- Главная раздела: поиск + темы ----------------

@Composable
private fun MainScreen(vm: HelpViewModel, state: HelpUiState, onBack: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Header("Вопросы и ответы", onBack) {
            TextButton(onClick = { vm.refresh() }, enabled = !state.loading) { Text("Обновить") }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Найти ответ") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Очистить")
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )

        if (state.loading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        ErrorText(state.error, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        when {
            state.categories.isEmpty() && state.loading -> Centered {
                CircularProgressIndicator()
            }
            state.categories.isEmpty() -> Centered {
                Text("Список вопросов пока не загрузился.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.refresh() }) { Text("Повторить") }
            }
            state.searching -> SearchResults(state, onArticle = { focus.clearFocus(); vm.openArticle(it) })
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(state.categories, key = { it.id }) { c ->
                    ItemRow(title = c.title, subtitle = c.description, onClick = { vm.openCategory(c) })
                }
                item { Bottom() }
            }
        }
    }
}

@Composable
private fun SearchResults(state: HelpUiState, onArticle: (QaArticle) -> Unit) {
    if (state.results.isEmpty()) {
        Centered {
            Text("Ничего не нашлось", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Попробуйте другие слова или спросите в чате поддержки.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            ChatButton()
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                "Нашлось: ${state.results.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        items(state.results, key = { it.url }) { a ->
            ItemRow(title = a.title, subtitle = a.categoryTitle, onClick = { onArticle(a) })
        }
        item { Bottom() }
    }
}

// ---------------- Тема: список статей ----------------

@Composable
private fun CategoryScreen(category: QaCategory, onBack: () -> Unit, onArticle: (QaArticle) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(category.title, onBack)
        if (category.articles.isEmpty()) {
            Centered { Text("В этой теме пока нет статей.") }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (category.description != null) {
                item {
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            items(category.articles, key = { it.id + it.url }) { a ->
                ItemRow(title = a.title, subtitle = a.description, onClick = { onArticle(a) })
            }
            item { Bottom() }
        }
    }
}

// ---------------- Статья: страница сайта t2 ----------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArticleScreen(article: QaArticle, onBack: () -> Unit) {
    val context = LocalContext.current
    var progress by remember(article.url) { mutableIntStateOf(0) }
    // Внутри приложения показываем только страницы сайта t2 по https; остальное — кнопкой в браузер.
    val startUrl = remember(article.url) { inAppUrl(article.url) }
    val inApp = remember(article.url) { isT2PageUrl(startUrl) }
    var failed by remember(article.url) {
        mutableStateOf<String?>(if (inApp) null else "Эта страница не на сайте t2. Откройте её в браузере.")
    }
    var canGoBack by remember(article.url) { mutableStateOf(false) }
    val webView = remember(article.url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true // сайт t2 без него пустой
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Странице незачем читать файлы телефона и грузить незащищённые (http) части.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    // Страницы t2 — здесь же, всё остальное (звонок, почта, чужие сайты) — в своём приложении.
                    if ((scheme == "https" || scheme == "http") && isT2Host(uri.host)) {
                        if (scheme == "http" && request.isForMainFrame) {
                            // Незащищённую ссылку на сайт t2 открываем по https.
                            view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                            return true
                        }
                        return false
                    }
                    openExternal(view.context, uri)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    failed = null
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        failed = "Страница не открылась. Проверьте интернет и попробуйте ещё раз."
                    }
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            if (inApp) loadUrl(startUrl)
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    // Ссылки внутри статьи: «Назад» сначала по ним, потом — к списку.
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Header(article.title, onBack) {
            TextButton(onClick = { openExternal(context, Uri.parse(webView.url ?: article.url)) }) {
                Text("В браузере")
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            val err = failed
            if (err != null) {
                Surface(Modifier.fillMaxSize()) {
                    Centered {
                        Text(err, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        if (inApp) {
                            Button(onClick = { failed = null; webView.reload() }) { Text("Повторить") }
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedButton(onClick = { openExternal(context, Uri.parse(article.url)) }) {
                            Text("Открыть в браузере")
                        }
                    }
                }
            }
        }
    }
}

private fun openExternal(context: Context, uri: Uri) {
    // Ссылки на файлы телефона, скрипты и т.п. другим приложениям не передаём.
    if (isUnsafeScheme(uri.scheme)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Нечем открыть (или система не разрешила) — просто остаёмся на странице.
    }
}

// ---------------- Общие кусочки ----------------

@Composable
private fun ItemRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        } else null,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
    )
}

/** Низ списка: если ответа нет — чат поддержки. */
@Composable
private fun Bottom() {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Не нашли ответ?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChatButton()
    }
}

@Composable
private fun ChatButton() {
    val context = LocalContext.current
    OutlinedButton(onClick = { ChatActivity.start(context) }) { Text("Написать в чат поддержки") }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
