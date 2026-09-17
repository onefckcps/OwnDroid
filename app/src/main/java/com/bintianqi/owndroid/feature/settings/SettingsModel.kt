package com.bintianqi.owndroid.feature.settings

import androidx.annotation.Keep
import com.bintianqi.owndroid.R
import kotlinx.serialization.Serializable

@Serializable
data class MySettings(
    val privilege: Privilege = Privilege(),
    var theme: Theme = Theme(),
    val appLock: AppLock = AppLock(),
    val shortcut: Shortcut = Shortcut(),
    val notifications: MutableList<Int> = mutableListOf(),
    var displayDangerousFeatures: Boolean = false,
    var appFeatureSwitchView: Boolean = true,
    var cpifChanged: Boolean = false, // Cross profile intent filter
    val api: Api = Api(),
    // Whether the time blocker service should run. Default true: the service is
    // meant to be always-on once rules exist, unless the user explicitly stops it.
    var timeBlockerServiceEnabled: Boolean = true,
    // Hardcore mode: manual session end (epoch ms, 0 = no manual session)
    var hardcoreManualUntilEpochMs: Long = 0,
    // Whether the hardcore service should run (like timeBlockerServiceEnabled)
    var hardcoreServiceEnabled: Boolean = true,
    // Hardcore mode: Private DNS hostname enforced while active
    var hardcoreDnsHost: String = "family.cloudflare-dns.com",
    // Hardcore mode: whether to enforce Private DNS while active
    var hardcoreDnsEnforcement: Boolean = true,
    // Hardcore mode: enforce black minimal launcher as home while active
    var hardcoreMinimalLauncher: Boolean = true
) {
    @Serializable
    data class Privilege(
        var dhizuku: Boolean = false,
        var dhizukuServer: Boolean = false,
        var managedProfileActivated: Boolean = false,
        var defaultAffiliationIdSet: Boolean = false,
    )

    // Use `val` since it is used as UI state
    @Serializable
    data class Theme(
        val materialYou: Boolean = true,
        val dark: DarkMode = DarkMode.FollowSystem,
        val black: Boolean = false,
    )

    @Keep
    enum class DarkMode(val text: Int) {
        FollowSystem(R.string.follow_system), On(R.string.on), Off(R.string.off)
    }

    @Serializable
    data class AppLock(
        var passwordHash: String = "",
        var biometrics: Boolean = false,
        var lockWhenLeaving: Boolean = false,
        var totp: Boolean = false,
    ) {
        val isActive: Boolean
            get() = passwordHash.isNotEmpty() || totp
    }

    @Serializable
    data class Shortcut(
        var enabled: Boolean = true,
        var key: String = "",
    )

    @Serializable
    data class Api(
        var enabled: Boolean = false,
        var key: String = ""
    )
}
