package com.starchive.player.aa

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.starchive.player.R

/** Entry point for Android Auto. Hosts are restricted to the known Android Auto projection package. */
class StArchiveCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.Builder(this)
            .addAllowedHosts(R.array.aa_hosts_allowlist)
            .build()
    }

    override fun onCreateSession(): Session = StArchiveCarSession()
}
