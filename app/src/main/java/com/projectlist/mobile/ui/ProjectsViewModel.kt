package com.projectlist.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectlist.mobile.data.Project
import com.projectlist.mobile.data.Repository
import com.projectlist.mobile.data.SessionStore
import com.projectlist.mobile.data.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ProjectsUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val fromCache: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val filtered: List<Project>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return projects
            return projects.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }

    val totalTasks: Int get() = projects.sumOf { it.data.tasks.size }
    val doneTasks: Int get() = projects.sumOf { project -> project.data.tasks.count { it.done } }
    val totalCommits: Int get() = projects.sumOf { it.data.commits.size }
}

class ProjectsViewModel(
    private val repository: Repository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.projectsFlow.collect { list ->
                if (list != null) {
                    _state.update {
                        it.copy(
                            projects = list.sortedByDescending { p -> p.updatedAt ?: p.createdAt },
                            loading = false,
                        )
                    }
                }
            }
        }
        load()
    }

    fun load(force: Boolean = false) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            val cached = if (_state.value.projects.isEmpty()) repository.cachedProjects() else emptyList()
            _state.update {
                it.copy(
                    loading = it.projects.isEmpty() && cached.isEmpty(),
                    refreshing = it.projects.isNotEmpty() || cached.isNotEmpty(),
                    fromCache = it.projects.isEmpty() && cached.isNotEmpty(),
                    projects = if (it.projects.isEmpty()) cached else it.projects,
                    error = null,
                )
            }
            try {
                repository.loadProjects()
                _state.update { it.copy(loading = false, refreshing = false, error = null, fromCache = false) }
            } catch (t: Throwable) {
                if (!session.current.hasAccess) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = friendlyError(t),
                        fromCache = it.projects.isNotEmpty(),
                    )
                }
            }
        }
    }

    fun refresh() = load(force = true)

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun createProject(name: String, description: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repository.createProject(name.trim(), description.trim())
                _state.update { it.copy(busy = false, message = "Проект создан") }
                onResult(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onResult(false)
            }
        }
    }

    fun deleteProject(project: Project, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repository.deleteProject(project.id)
                _state.update {
                    it.copy(
                        busy = false,
                        projects = it.projects.filterNot { p -> p.id == project.id },
                        message = "Проект «${project.name}» удалён",
                    )
                }
                onResult(true)
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false, message = friendlyError(t)) }
                onResult(false)
            }
        }
    }

    fun toggleTask(projectId: String, index: Int) {
        val project = _state.value.projects.find { it.id == projectId } ?: return
        val tasks = project.data.tasks
        if (index !in tasks.indices) return
        val next = tasks.toMutableList().also { list ->
            list[index] = list[index].copy(done = !list[index].done)
        }
        viewModelScope.launch {
            try {
                repository.patchProject(
                    id = projectId,
                    data = buildJsonObject {
                        put("tasks", buildJsonArray {
                            next.forEach { task ->
                                add(buildJsonObject {
                                    put("title", task.title)
                                    put("done", task.done)
                                })
                            }
                        })
                    },
                )
            } catch (t: Throwable) {
                _state.update { it.copy(message = friendlyError(t)) }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
