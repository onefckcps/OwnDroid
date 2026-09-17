package com.bintianqi.owndroid.feature.hardcore

import com.bintianqi.owndroid.feature.time_blocker.TimeWindow
import kotlinx.serialization.Serializable

@Serializable
data class HardcoreConfig(
    val dnsHost: String = "family.cloudflare-dns.com",
    val dnsEnforcementEnabled: Boolean = true
)

@Serializable
data class HardcoreSchedule(
    val id: Int = 0,
    val window: TimeWindow,
    val enabled: Boolean = true
)
