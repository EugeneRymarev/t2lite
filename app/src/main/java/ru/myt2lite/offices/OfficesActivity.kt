package ru.myt2lite.offices

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ru.myt2lite.data.Errors
import ru.myt2lite.ui.ErrorText
import ru.myt2lite.ui.theme.T2LiteTheme

/**
 * «Салоны связи t2»: карта и список салонов с адресами и часами работы, поиск по городу и адресу.
 *
 * Как в оригинальном приложении (6.52.0): у t2 нет отдельного API со списком салонов —
 * оригинал открывает страницу сайта t2.ru/offices внутри приложения (адрес по умолчанию
 * в его настройках: https://t2.ru/offices?utm_source=my_tele2&utm_medium=app&mode=webView,
 * mode=webView убирает шапку и подвал сайта). Делаем так же.
 *
 * Вход не нужен — страница открытая. Сайт сначала проверяет, что открыл его браузер
 * (короткая страница «T2 - validation»), поэтому включены JavaScript и cookies.
 *
 * Что умеет экран:
 *  - «Назад» листает назад по страницам сайта, с первой — закрывает экран;
 *  - ⟳ — обновить; «В браузере» — открыть ту же страницу в обычном браузере;
 *  - кнопка «рядом со мной» на сайте спросит доступ к местоположению (только по нажатию);
 *  - телефоны, маршруты, карты и чужие сайты открываются в своих приложениях.
 */
class OfficesActivity : ComponentActivity() {

    companion object {
        const val OFFICES_URL = "https://t2.ru/offices?mode=webView"

        fun start(context: Context) {
            context.startActivity(Intent(context, OfficesActivity::class.java))
        }
    }

    /** Ответ сайту на вопрос «можно узнать, где вы?», пока Android спрашивает разрешение. */
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.any { it }
            pendingGeo?.let { (origin, callback) -> callback.invoke(origin, granted, false) }
            pendingGeo = null
        }

    fun askLocation(origin: String, callback: GeolocationPermissions.Callback) {
        val has = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (has) {
            callback.invoke(origin, true, false)
            return
        }
        pendingGeo?.let { (o, c) -> c.invoke(o, false, false) }
        pendingGeo = origin to callback
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            T2LiteTheme {
                Surface(Modifier.fillMaxSize()) {
                    OfficesScreen(onBack = { finish() })
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(true)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Странице незачем читать файлы телефона.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase()
                    // Страницы сайта t2 (и всё, что грузится внутри карты) — остаются здесь.
                    if ((scheme == "https" || scheme == "http") &&
                        (!request.isForMainFrame || isT2Host(uri.host))
                    ) return false
                    // Остальное (tel:, geo:, intent:, Яндекс.Карты, чужие сайты) — в своём приложении.
                    openOutside(view.context, uri)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    error = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    if (!request.isForMainFrame) return
                    error = Errors.build(
                        "Не получилось открыть список салонов. Проверьте интернет и нажмите «Повторить».",
                        "${err.errorCode}: ${err.description}\n${request.url}"
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse
                ) {
                    // 503 — это проверка «вы не робот?» на самом сайте: она сама пройдёт и перезагрузит
                    // страницу, поэтому её ошибкой не считаем.
                    if (!request.isForMainFrame || response.statusCode == 503) return
                    if (response.statusCode >= 400) {
                        error = Errors.build(
                            "Сайт t2 сейчас не показывает салоны. Попробуйте чуть позже.",
                            "HTTP ${response.statusCode} ${response.reasonPhrase ?: ""}\n${request.url}"
                        )
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    val activity = context as? OfficesActivity
                    // Местоположение отдаём только самому сайту t2 (по https), а не встроенным чужим страницам.
                    val fromT2 = runCatching { Uri.parse(origin) }.getOrNull()
                        ?.let { it.scheme.equals("https", ignoreCase = true) && isT2Host(it.host) } == true
                    if (activity == null || !fromT2) callback.invoke(origin, false, false)
                    else activity.askLocation(origin, callback)
                }
            }

            loadUrl(OfficesActivity.OFFICES_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(
                "Салоны связи",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { openOutside(context, Uri.parse(webView.url ?: OfficesActivity.OFFICES_URL)) }) {
                Text("В браузере")
            }
            IconButton(onClick = {
                error = null
                if (webView.url.isNullOrBlank()) webView.loadUrl(OfficesActivity.OFFICES_URL) else webView.reload()
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        if (loading) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            val err = error
            if (err != null) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ErrorText(err, style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = {
                            error = null
                            webView.loadUrl(OfficesActivity.OFFICES_URL)
                        }) { Text("Повторить") }
                        Text(
                            "Можно открыть список салонов на сайте t2.ru",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = { openOutside(context, Uri.parse(OfficesActivity.OFFICES_URL)) }) {
                            Text("Открыть в браузере")
                        }
                    }
                }
            }
        }
    }
}

/** Сайт t2 (t2.ru, msk.t2.ru и т.п.) — открываем внутри экрана. */
private fun isT2Host(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "t2.ru" || h.endsWith(".t2.ru") || h == "tele2.ru" || h.endsWith(".tele2.ru")
}

/** Ссылки, которые нельзя отдавать другим приложениям: файлы телефона, скрипты и т.п. */
private fun isUnsafeScheme(scheme: String?): Boolean = when (scheme?.lowercase()) {
    null, "file", "content", "javascript", "data", "about", "blob", "android.resource", "chrome" -> true
    else -> false
}

/** Открыть ссылку в подходящем приложении (звонилка, карты, браузер). */
private fun openOutside(context: Context, uri: Uri) {
    val isIntentLink = uri.scheme.equals("intent", ignoreCase = true)
    val prepared: Intent? = if (isIntentLink) {
        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                // Сайт не должен уметь выдавать другим приложениям доступ к нашим данным.
                flags = flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    ).inv()
            }
            // Внутри intent:-ссылки тоже не должно быть адресов файлов телефона и т.п.
            ?.takeIf { it.data == null || !isUnsafeScheme(it.data?.scheme) }
    } else if (isUnsafeScheme(uri.scheme)) {
        null
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    val intent = prepared ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Нет подходящего приложения — для intent: пробуем запасную ссылку из самой ссылки (только http/https).
        val fallback = if (isIntentLink) {
            runCatching {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
        } else null
        val fallbackUri = fallback?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val fallbackScheme = fallbackUri?.scheme?.lowercase()
        if (fallbackUri != null && (fallbackScheme == "https" || fallbackScheme == "http")) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
