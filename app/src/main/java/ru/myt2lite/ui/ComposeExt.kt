package ru.myt2lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

/** Небольшой помощник, чтобы не тянуть лишние импорты в экраны. */
@Composable
fun <T> StateFlow<T>.collectAsStateValue(): T {
    val s: State<T> = collectAsState()
    return s.value
}
