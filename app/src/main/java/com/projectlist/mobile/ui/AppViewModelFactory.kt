package com.projectlist.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.projectlist.mobile.AppContainer

class AppViewModelFactory(
    private val container: AppContainer,
    private val projectId: String? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ProjectsViewModel::class.java) ->
            ProjectsViewModel(container.repository, container.session) as T

        modelClass.isAssignableFrom(NotificationsViewModel::class.java) ->
            NotificationsViewModel(container.repository, container.session) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container.repository, container.session) as T

        modelClass.isAssignableFrom(ProjectDetailViewModel::class.java) -> {
            val id = projectId ?: throw IllegalArgumentException("projectId is required")
            ProjectDetailViewModel(container.application, container.repository, container.session, id) as T
        }

        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
