package io.github.santiquiroz.blindside.wear.session

class BlindsideSessionService : SessionService() {
    override val host: SessionHost = WearSessionHost
}
