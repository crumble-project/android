package dev.crumble.android.model

fun greeting(hour: Int): String = when {
    hour < 5 -> "Up late"
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
}

/** What the home screen says under a bot's name. */
fun botStatusLine(agent: Agent, working: Boolean): String = when {
    working -> "Working…"
    agent.enabled -> agent.description?.nilIfEmpty ?: "Ready"
    else -> "Paused"
}
