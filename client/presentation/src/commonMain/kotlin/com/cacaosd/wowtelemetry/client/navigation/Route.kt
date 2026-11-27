package com.cacaosd.wowtelemetry.client.navigation

/**
 * Navigation destinations. Only the top-level destinations exist today; a detail
 * screen (e.g. an encounter) becomes a new [Route] pushed on top of its tab.
 */
sealed interface Route {
    /** Stable identifier used to persist the back stack across process death. */
    val id: String

    data object Chronicle : Route {
        override val id = "chronicle"
    }

    data object Attachments : Route {
        override val id = "attachments"
    }

    data object Settings : Route {
        override val id = "settings"
    }

    companion object {
        fun fromId(id: String): Route? = TopLevelRoutes.firstOrNull { it.id == id }
    }
}

/** Destinations shown in the navigation bar / rail, in display order. */
val TopLevelRoutes: List<Route> = listOf(Route.Chronicle, Route.Attachments, Route.Settings)
