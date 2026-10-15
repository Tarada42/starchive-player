package com.starchive.player

import android.app.Application
import com.starchive.player.data.Repository

class StArchiveApp : Application() {
    val repository: Repository by lazy { Repository(applicationContext) }
}
