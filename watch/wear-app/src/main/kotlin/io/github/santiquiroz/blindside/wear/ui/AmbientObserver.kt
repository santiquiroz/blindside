package io.github.santiquiroz.blindside.wear.ui

import android.app.Activity
import androidx.wear.ambient.AmbientLifecycleObserver
import io.github.santiquiroz.blindside.shared.session.SessionStore

fun createAmbientObserver(activity: Activity): AmbientLifecycleObserver =
    AmbientLifecycleObserver(activity, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) = SessionStore.setAmbient(true)

        override fun onExitAmbient() = SessionStore.setAmbient(false)

        override fun onUpdateAmbient() = Unit
    })
