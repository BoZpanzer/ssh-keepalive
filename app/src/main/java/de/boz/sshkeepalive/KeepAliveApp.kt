package de.boz.sshkeepalive

import android.app.Application
import de.boz.sshkeepalive.service.KeepAliveService

class KeepAliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KeepAliveService.createChannel(this)
    }
}
