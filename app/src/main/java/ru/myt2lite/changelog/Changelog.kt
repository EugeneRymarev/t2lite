package ru.myt2lite.changelog

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * История версий. Текст лежит в app/src/main/assets/changelog.md:
 *   ## 1.1.6 · build 12 · 22.11.2026   — заголовок версии
 *   - что поменялось                   — пункт
 *   ## Готовится                       — то, что попадёт в следующую сборку (build.ps1 сам поставит номер и дату)
 * Строки «# ...» — комментарии, в приложении не показываются.
 */
data class ChangelogEntry(
    val version: String,          // «1.1.6»; для «Готовится» — пусто
    val build: Long?,             // 12
    val date: LocalDate?,         // 22.11.2026
    val items: List<String>,
    val pending: Boolean = false  // раздел «Готовится» (ещё не вышло)
)

/** Установленная версия: имя (1.1.6) и номер сборки (12). */
data class InstalledVersion(val name: String, val code: Long)

object Changelog {
    private const val ASSET = "changelog.md"
    private const val PENDING = "готовится"
    private val buildRe = Regex("""build\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val dateRe = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

    fun installed(context: Context): InstalledVersion = runCatching {
        @Suppress("DEPRECATION")
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        InstalledVersion(pi.versionName ?: "?", PackageInfoCompat.getLongVersionCode(pi))
    }.getOrDefault(InstalledVersion("?", 0))

    fun load(context: Context): List<ChangelogEntry> = runCatching {
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    fun parse(text: String): List<ChangelogEntry> {
        val result = mutableListOf<ChangelogEntry>()
        var header: String? = null
        val items = mutableListOf<String>()

        fun flush() {
            val h = header ?: return
            val entry = parseHeader(h, items.toList())
            // Пустой «Готовится» не показываем
            if (!(entry.pending && entry.items.isEmpty())) result += entry
            items.clear()
        }

        for (raw in text.removePrefix("﻿").lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> { flush(); header = line.removePrefix("## ").trim() }
                line.startsWith("#") || line.isEmpty() -> Unit
                header == null -> Unit
                line.startsWith("- ") || line.startsWith("* ") -> items += line.substring(2).trim()
                items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + line // продолжение пункта
                else -> items += line
            }
        }
        flush()
        return result
    }

    private fun parseHeader(h: String, items: List<String>): ChangelogEntry {
        if (h.lowercase(Locale.ROOT).startsWith(PENDING)) {
            return ChangelogEntry("", null, null, items, pending = true)
        }
        val parts = h.split('·', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val version = parts.firstOrNull().orEmpty()
        val build = parts.firstNotNullOfOrNull { buildRe.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val date = parts.firstNotNullOfOrNull { p ->
            dateRe.find(p)?.let { m ->
                runCatching {
                    LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                }.getOrNull()
            }
        }
        return ChangelogEntry(version, build, date, items)
    }

    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

    /** «22 ноября 2026». */
    fun formatDate(date: LocalDate): String = date.format(dateFmt)
}
