package dev.crumble.android.model

/** What the home screen says under a bot's name. */
fun botStatusLine(agent: Agent, working: Boolean): String = when {
    working -> "Working…"
    agent.enabled -> agent.description?.nilIfEmpty ?: "Ready"
    else -> "Paused"
}
