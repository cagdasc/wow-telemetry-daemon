package com.cacaosd.wowtelemetry.domain.preferences

/**
 * Configures the depth, granularity, and structure of LLM combat analysis.
 */
enum class EffortMode(
    val displayName: String,
    val description: String,
) {
    /**
     * Rapid in-combat feedback. Strict 1-2 sentence budget per section, zero fluff, maximum token efficiency.
     */
    CONCISE(
        displayName = "Tactical / Brief",
        description = "Rapid in-combat feedback. Ultra-compact summaries and top priority fixes.",
    ),

    /**
     * Balanced combat analysis. Standard overview, rotational feedback, death summaries, and actionable advice.
     */
    BALANCED(
        displayName = "Analytical / Standard",
        description = "Balanced overview of combat performance, skill usage, wipe causes, and next steps.",
    ),

    /**
     * Full post-combat audit. Detailed timestamp analysis, rotational sequence breakdowns, and deep wipe timeline.
     */
    COMPREHENSIVE(
        displayName = "Deep Dive / Comprehensive",
        description = "Exhaustive combat audit with rotational sequence checks, cooldown efficiency, and wipe timelines.",
    ),
}
