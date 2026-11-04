package com.projectlist.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectlist.mobile.data.NotificationItem
import com.projectlist.mobile.data.Repository
import com.projectlist.mobile.data.SessionStore
import com.projectlist.mobile.data.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val loading: Boolean = false,
    val items: List<NotificationItem> = emptyList(),
    val unread: Int = 0,
    val error: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class NotificationsViewModel(
    private val repository: Repository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    private var lastLoadedAt = 0L

    fun load(showSpinner: Boolean = false) {
        if (showSpinner || _state.value.items.isEmpty()) {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
        }
        viewModelScope.launch {
            try {
                val result = repository.notifications()
                lastLoadedAt = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        loading = false,
                        items = result.notifications,
                        unread = result.unread,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                if (!session.current.hasAccess) return@launch
                _state.update { it.copy(loading = false, error = friendlyError(t)) }
            }
        }
    }

    fun loadIfStale() {
        if (System.currentTimeMillis() - lastLoadedAt > 15_000) load()
    }

    fun markAllRead() {
        val items = _state.value.items
        if (items.isEmpty()) return
        _state.update {
            it.copy(
                items = it.items.map { item -> item.copy(read = true) },
                unread = 0,
            )
        }
        viewModelScope.launch {
            try {
                repository.markAllNotificationsRead()
            } catch (t: Throwable) {
                if (session.current.loggedIn) _state.update { it.copy(message = friendlyError(t)) }
                load()
            }
        }
    }

    fun markRead(item: NotificationItem) {
        if (item.read) return
        _state.update {
            it.copy(
                items = it.items.map { n -> if (n.id == item.id) n.copy(read = true) else n },
                unread = (it.unread - 1).coerceAtLeast(0),
            )
        }
        viewModelScope.launch {
            try {
                repository.markNotificationRead(item.id)
            } catch (t: Throwable) {
                if (session.current.loggedIn) {
                    _state.update { it.copy(message = friendlyError(t)) }
                    load()
                }
            }
        }
    }

    fun delete(item: NotificationItem) {
        _state.update {
            it.copy(
                items = it.items.filterNot { n -> n.id == item.id },
                unread = if (item.read) it.unread else (it.unread - 1).coerceAtLeast(0),
            )
        }
        viewModelScope.launch {
            try {
                repository.deleteNotification(item.id)
            } catch (t: Throwable) {
                if (session.current.loggedIn) {
                    _state.update { it.copy(message = friendlyError(t)) }
                    load()
                }
            }
        }
    }

    fun clearAll() {
        _state.update { it.copy(items = emptyList(), unread = 0) }
        viewModelScope.launch {
            try {
                repository.clearNotifications()
            } catch (t: Throwable) {
                if (session.current.loggedIn) {
                    _state.update { it.copy(message = friendlyError(t)) }
                    load()
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
