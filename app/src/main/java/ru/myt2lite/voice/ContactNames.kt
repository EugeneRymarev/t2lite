package ru.myt2lite.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Имя из телефонной книги по номеру звонившего (ContactsContract.PhoneLookup).
 * PhoneLookup сам сравнивает номера без учёта формата (+7…, 8…, пробелы, дефисы).
 * Без разрешения READ_CONTACTS всегда возвращает null — тогда на экране просто номер.
 */
object ContactNames {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Имя контакта или null (нет разрешения / номер скрыт / нет в книге). Вызывать не на главном потоке. */
    fun lookup(context: Context, number: String?): String? {
        if (!hasPermission(context)) return null
        val digits = number?.filter { it.isDigit() }.orEmpty()
        if (digits.length < 5) return null
        // Пробуем «+7XXXXXXXXXX», затем «8XXXXXXXXXX» и номер как пришёл — на случай, если в книге записан иначе.
        val variants = buildList {
            dialableVoiceNumber(number)?.let { add(it) }
            if (digits.length == 11 && (digits[0] == '7' || digits[0] == '8')) add("8" + digits.substring(1))
            number?.trim()?.let { add(it) }
        }.distinct()
        for (v in variants) {
            val name = query(context, v)
            if (!name.isNullOrBlank()) return name
        }
        return null
    }

    private fun query(context: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w("T2Voice", "контакты: ${e.message}")
        null
    }
}
