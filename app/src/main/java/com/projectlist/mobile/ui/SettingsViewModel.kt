package com.projectlist.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectlist.mobile.data.HealthInfo
import com.projectlist.mobile.data.KeyStatus
import com.projectlist.mobile.data.LogEntry
import com.projectlist.mobile.data.Repository
import com.projectlist.mobile.data.SessionStore
import com.projectlist.mobile.data.VersionInfo
import com.projectlist.mobile.data.friendlyError
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val health: HealthInfo? = null,
    val version: VersionInfo? = null,
    val keyStatus: KeyStatus? = null,
    val error: String? = null,
    val logs: List<LogEntry> = emptyList(),
    val logsLoading: Boolean = false,
    val logsError: String? = null,
)

class SettingsViewModel(
    private val repository: Repository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.health == null, error = null) }
            try {
                coroutineScope {
                    val health = async { runCatching { repository.health() }.getOrNull() }
                    val version = async { runCatching { repository.versionInfo() }.getOrNull() }
                    val key = async { runCatching { repository.keyStatus() }.getOrNull() }
                    _state.update {
                        it.copy(
                            loading = false,
                            health = health.await(),
                            version = version.await(),
                            keyStatus = key.await(),
                            error = null,
                        )
                    }
                }
            } catch (t: Throwable) {
                if (session.current.hasAccess) {
                    _state.update { it.copy(loading = false, error = friendlyError(t)) }
                }
            }
        }
    }

    fun loadLogs() {
        _state.update { it.copy(logsLoading = true, logsError = null) }
        viewModelScope.launch {
            try {
                val result = repository.logs(200)
                _state.update { it.copy(logsLoading = false, logs = result.entries.reversed()) }
            } catch (t: Throwable) {
                _state.update { it.copy(logsLoading = false, logsError = friendlyError(t)) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }

    fun forgetDevice() {
        repository.forgetDevice()
    }
}
