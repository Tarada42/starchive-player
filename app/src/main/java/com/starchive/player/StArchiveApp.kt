package com.starchive.player

import android.app.Application
import com.starchive.player.data.DownloadManager
import com.starchive.player.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class StArchiveApp : Application() {
    val repository: Repository by lazy { Repository(applicationContext) }
    val downloadManager: DownloadManager by lazy {
        DownloadManager(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
