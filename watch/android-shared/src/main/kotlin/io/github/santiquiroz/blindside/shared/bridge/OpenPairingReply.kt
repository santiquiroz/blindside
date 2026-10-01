package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.canOpenPairingWindow
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault

enum class OpenPairingReply { REQUESTED, NO_LINK }

fun openPairingReplyFor(session: SessionUiState): OpenPairingReply =
    if (canOpenPairingWindow(session)) OpenPairingReply.REQUESTED else OpenPairingReply.NO_LINK

fun encodeOpenPairingReply(reply: OpenPairingReply): ByteArray = reply.name.toByteArray(Charsets.UTF_8)

fun decodeOpenPairingReply(bytes: ByteArray): OpenPairingReply =
    enumOrDefault(bytes.toString(Charsets.UTF_8), OpenPairingReply.NO_LINK)
