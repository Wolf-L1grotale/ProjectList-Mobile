package com.projectlist.mobile

import android.app.Application
import android.content.Context
import com.projectlist.mobile.data.ApiClient
import com.projectlist.mobile.data.PairingClient
import com.projectlist.mobile.data.Repository
import com.projectlist.mobile.data.SessionStore
import com.projectlist.mobile.data.offline.OfflineStore

class AppContainer(context: Context) {
    val application: Application = context.applicationContext as Application
    val session = SessionStore(application)
    val api = ApiClient(session)
    val offline = OfflineStore(session)
    val pairing = PairingClient()
    val repository = Repository(api, session, offline, application, pairing)
}

class PLApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
