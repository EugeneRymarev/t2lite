package ru.myt2lite.help

import com.google.gson.annotations.SerializedName

/*
 * Раздел «Вопросы и ответы» — как в оригинале 6.52.0 (пакеты ru.tele2.mytele2.qa
 * и presentation.support.qa):
 *   GET api/subscribers/{номер}/articles/support/categories
 *   → {"data": [ {id, name, slug, description, articles: [ {id, name, slug, url, description} ]} ], "meta": {...}}
 * Одним запросом приходят все темы вместе со статьями. Поиск в оригинале — по названиям
 * статей прямо на телефоне, статья открывается веб-страницей по её url.
 */

data class QaArticleDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("description") val description: String? = null
)

data class QaCategoryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("articles") val articles: List<QaArticleDto>? = null
)

/** Статья: заголовок, короткое описание и адрес страницы с ответом. */
data class QaArticle(
    val id: String,
    val title: String,
    val description: String?,
    val url: String,
    val categoryId: String,
    val categoryTitle: String
)

/** Тема («Баланс и расходы», «SIM-карта, номер и тариф»…) со своими статьями. */
data class QaCategory(
    val id: String,
    val title: String,
    val description: String?,
    val articles: List<QaArticle>
)

internal fun List<QaCategoryDto>.toCategories(): List<QaCategory> =
    mapIndexedNotNull { i, c ->
        val title = plain(c.name).orEmpty()
        if (title.isEmpty()) return@mapIndexedNotNull null
        val catId = c.id?.takeIf { it.isNotBlank() } ?: c.slug?.takeIf { it.isNotBlank() } ?: "cat$i"
        val articles = c.articles.orEmpty().mapIndexedNotNull { j, a ->
            val name = plain(a.name).orEmpty()
            val url = absoluteUrl(a.url)
            if (name.isEmpty() || url == null) null
            else QaArticle(
                id = a.id?.takeIf { it.isNotBlank() } ?: a.slug?.takeIf { it.isNotBlank() } ?: "$catId/$j",
                title = name,
                description = plain(a.description),
                url = url,
                categoryId = catId,
                categoryTitle = title
            )
        }
        QaCategory(catId, title, plain(c.description), articles)
    }

/**
 * t2 иногда присылает описание с HTML-разметкой («<p>Всё о Т2 карте</p>») — превращаем в обычный текст:
 * убираем теги, раскрываем &nbsp; и т.п., лишние пробелы и переносы схлопываем.
 */
internal fun plain(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    val text = if (s.contains('<') || s.contains('&')) {
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
    } else s
    return text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}

/** Если сервер прислал адрес без сайта («/help/…»), дописываем t2.ru. */
internal fun absoluteUrl(raw: String?): String? {
    val u = raw?.trim().orEmpty()
    return when {
        u.isEmpty() -> null
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "https://t2.ru$u"
        else -> "https://t2.ru/$u"
    }
}

/**
 * Адрес для просмотра внутри приложения. Оригинал открывает страницы t2.ru с `mode=webView`
 * (так сайт показывается без своей шапки и подвала, как в «Салонах связи») — делаем так же.
 */
fun inAppUrl(url: String): String {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host.orEmpty()
    val isT2 = host == "t2.ru" || host.endsWith(".t2.ru")
    if (!isT2) return url
    val insecure = uri.scheme.equals("http", ignoreCase = true)
    val hasMode = uri.getQueryParameter("mode") != null
    if (!insecure && hasMode) return url
    val b = uri.buildUpon()
    if (insecure) b.scheme("https") // незащищённый адрес сайта t2 открываем по https
    if (!hasMode) b.appendQueryParameter("mode", "webView")
    return b.build().toString()
}

/** Сайт t2 (t2.ru, msk.t2.ru, tele2.ru и т.п.). */
internal fun isT2Host(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "t2.ru" || h.endsWith(".t2.ru") || h == "tele2.ru" || h.endsWith(".tele2.ru")
}

/**
 * Можно ли показывать страницу внутри приложения: только сайт t2 и только по защищённому https.
 * Адрес статьи присылает сервер — чужой сайт внутри приложения (с включённым JavaScript) не открываем.
 */
fun isT2PageUrl(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && isT2Host(uri.host)
}

/** Ссылки, которые нельзя отдавать другим приложениям: файлы телефона, скрипты и т.п. */
internal fun isUnsafeScheme(scheme: String?): Boolean = when (scheme?.lowercase()) {
    null, "file", "content", "javascript", "data", "about", "blob", "android.resource", "chrome" -> true
    else -> false
}

/**
 * Поиск как в оригинале: статьи, в названии которых есть набранный текст (без учёта регистра).
 * Дополнительно «ё» = «е» и ищем каждое слово по отдельности — так находится больше.
 * Одна и та же статья бывает в нескольких темах — показываем её один раз.
 */
fun searchArticles(categories: List<QaCategory>, query: String): List<QaArticle> {
    val words = norm(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val seen = HashSet<String>()
    return categories.asSequence()
        .flatMap { it.articles.asSequence() }
        .filter { a ->
            val hay = norm(a.title + " " + (a.description ?: ""))
            words.all { hay.contains(it) }
        }
        .filter { seen.add(it.url) }
        .sortedBy { a -> if (words.all { norm(a.title).contains(it) }) 0 else 1 }
        .toList()
}

private fun norm(s: String): String =
    s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
