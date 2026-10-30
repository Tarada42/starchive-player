package com.starchive.player.aa

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto.
 *
 * For DEVELOPMENT builds (debuggable APK, e.g. the DHU / local test APK) allow all hosts so
 * the app always appears and connects under test. For RELEASE builds, restrict to the standard
 * Android host allow-list so only real signed hosts can connect.
 */
class StArchiveCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = StArchiveCarSession()
}
