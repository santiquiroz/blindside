package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.shared.session.SessionService

class BlindsideSessionService : SessionService() {
    override val host: SessionHost = WearSessionHost
}
