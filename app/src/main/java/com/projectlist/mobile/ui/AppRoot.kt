package com.projectlist.mobile.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.projectlist.mobile.PLApp

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val PROJECT = "project/{id}"

    fun project(id: String) = "project/${Uri.encode(id)}"
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PLApp).container }
    val navController = rememberNavController()
    val session by container.session.state.collectAsStateWithLifecycle()
    val lock: AppLockViewModel = viewModel()

    // Блокировка нужна, только если приложение стартовало уже с доступом
    // (сохранённая сессия или автономный режим). Экран входа и сопряжения
    // блокировать нельзя.
    val requireUnlock = remember { container.session.current.hasAccess }

    val startDestination = remember {
        if (container.session.current.hasAccess) Routes.HOME else Routes.LOGIN
    }

    if (requireUnlock && !lock.unlocked) {
        AppUnlockScreen(onUnlocked = lock::unlock)
        return
    }

    LaunchedEffect(session.hasAccess) {
        if (!session.hasAccess) {
            val current = navController.currentDestination?.route
            if (current != null && current != Routes.LOGIN) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(Routes.HOME) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(
                container = container,
                onLoggedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenProject = { id -> navController.navigate(Routes.project(id)) },
            )
        }
        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            ProjectDetailScreen(
                container = container,
                projectId = id,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
