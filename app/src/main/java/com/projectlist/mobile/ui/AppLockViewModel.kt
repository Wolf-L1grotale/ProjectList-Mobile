package com.projectlist.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Хранит состояние разблокировки в пределах процесса.
 *
 * ViewModel переживает поворот экрана, но не смерть процесса: после закрытия
 * или принудительной остановки приложения создаётся новый экземпляр, и
 * приложение снова требует отпечаток.
 */
class AppLockViewModel : ViewModel() {
    var unlocked by mutableStateOf(false)
        private set

    fun unlock() {
        unlocked = true
    }
}
