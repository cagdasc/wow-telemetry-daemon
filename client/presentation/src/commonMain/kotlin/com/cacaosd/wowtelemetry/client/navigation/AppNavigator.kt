package com.cacaosd.wowtelemetry.client.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Owns the back stack. [Route.Chronicle] is always the root, so Back from any other
 * top-level destination returns to Chronicle and Back on Chronicle leaves the app.
 */
@Stable
class AppNavigator internal constructor(initial: List<Route>) {
    val backStack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(START) }) }

    val current: Route get() = backStack.last()

    /** Switches to a top-level destination; the stack is reset to `[START, route]`. */
    fun navigateToTopLevel(route: Route) {
        if (route == current) return
        backStack.clear()
        backStack.add(START)
        if (route != START) backStack.add(route)
    }

    /** Pushes a non-top-level destination on top of the current one. */
    fun push(route: Route) {
        if (route != current) backStack.add(route)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    companion object {
        val START: Route = Route.Chronicle

        internal val Saver: Saver<AppNavigator, List<String>> = Saver(
            save = { navigator -> navigator.backStack.map { it.id } },
            restore = { ids -> AppNavigator(ids.mapNotNull(Route::fromId)) },
        )
    }
}

@Composable
fun rememberAppNavigator(): AppNavigator =
    rememberSaveable(saver = AppNavigator.Saver) { AppNavigator(listOf(AppNavigator.START)) }
