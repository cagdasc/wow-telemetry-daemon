package com.cacaosd.wowtelemetry.combatlog

/**
 * A group player detected in the combat log stream with resolved class and spec information.
 */
data class CombatPlayer(
    val guid: String,
    val name: String,
    val className: String?,
    val specName: String? = null,
)
