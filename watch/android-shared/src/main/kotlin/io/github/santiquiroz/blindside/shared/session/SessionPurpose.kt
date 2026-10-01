package io.github.santiquiroz.blindside.shared.session

enum class SessionPurpose { GAME, DIAGNOSTIC }

enum class StartTransition { KEEP, START, RESTART }

fun purposeFrom(name: String?): SessionPurpose = SessionPurpose.entries.firstOrNull { it.name == name } ?: SessionPurpose.GAME

fun startTransition(running: SessionPurpose?, requested: SessionPurpose): StartTransition = when (running) {
    null -> StartTransition.START
    requested -> StartTransition.KEEP
    else -> StartTransition.RESTART
}
