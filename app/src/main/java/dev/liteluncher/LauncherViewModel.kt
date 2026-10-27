package dev.liteluncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.liteluncher.data.LauncherStore
import dev.liteluncher.platform.AppsRepository

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    val store = LauncherStore(application)
    val apps = AppsRepository(application)

    override fun onCleared() {
        apps.close()
        store.close()
    }
}
