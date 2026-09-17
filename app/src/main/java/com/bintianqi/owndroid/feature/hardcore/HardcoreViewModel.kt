package com.bintianqi.owndroid.feature.hardcore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.feature.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HardcoreViewModel(
    val application: MyApplication,
    val repo: HardcoreRepository,
    val settingsRepo: SettingsRepository
) : ViewModel() {

    val allowlistState = MutableStateFlow(emptySet<String>())
    val schedulesState = MutableStateFlow(emptyList<HardcoreSchedule>())

    /** Live service running state */
    val serviceRunning: StateFlow<Boolean> = HardcoreService.runningState

    /** Whether hardcore mode is currently actively blocking */
    val isActive: StateFlow<Boolean> = HardcoreService.activeState

    /** Manual session end (epoch ms, 0 = none) */
    val manualUntilState = MutableStateFlow(0L)

    /** DNS host config */
    val dnsHostState = MutableStateFlow("family.cloudflare-dns.com")

    /** DNS enforcement toggle */
    val dnsEnforcementState = MutableStateFlow(true)

    /** Minimal launcher toggle (device owner / dhizuku only; applies on next activation) */
    val minimalLauncherState = MutableStateFlow(true)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            allowlistState.value = repo.getAllowlist()
            schedulesState.value = repo.getSchedules()
            manualUntilState.value = settingsRepo.data.hardcoreManualUntilEpochMs
            dnsHostState.value = settingsRepo.data.hardcoreDnsHost
            dnsEnforcementState.value = settingsRepo.data.hardcoreDnsEnforcement
            minimalLauncherState.value = settingsRepo.data.hardcoreMinimalLauncher
        }
    }

    // === Allowlist ===
    fun addToAllowlist(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.addToAllowlist(packageName)
            refresh()
            pokeService()
        }
    }

    fun removeFromAllowlist(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.removeFromAllowlist(packageName)
            refresh()
            pokeService()
        }
    }

    // === Schedules ===
    fun addSchedule(schedule: HardcoreSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.addSchedule(schedule)
            refresh()
            pokeService()
        }
    }

    fun updateSchedule(schedule: HardcoreSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.updateSchedule(schedule)
            refresh()
            pokeService()
        }
    }

    fun deleteSchedule(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteSchedule(id)
            refresh()
            pokeService()
        }
    }

    fun toggleScheduleEnabled(schedule: HardcoreSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.updateSchedule(schedule.copy(enabled = !schedule.enabled))
            refresh()
            pokeService()
        }
    }

    // === Manual session ===
    fun activateManually(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        settingsRepo.update { it.hardcoreManualUntilEpochMs = until }
        manualUntilState.value = until
        startService()
    }

    fun activateManuallyUntil(epochMs: Long) {
        settingsRepo.update { it.hardcoreManualUntilEpochMs = epochMs }
        manualUntilState.value = epochMs
        startService()
    }

    fun deactivate() {
        settingsRepo.update { it.hardcoreManualUntilEpochMs = 0 }
        manualUntilState.value = 0
        // Poke service so it detects the change immediately
        if (HardcoreService.isRunning) {
            HardcoreService.start(application) // re-triggers onStartCommand → new poll
        }
    }

    // === DNS config ===
    fun setDnsHost(host: String) {
        dnsHostState.value = host
        settingsRepo.update { it.hardcoreDnsHost = host }
    }

    fun setDnsEnforcement(enabled: Boolean) {
        dnsEnforcementState.value = enabled
        settingsRepo.update { it.hardcoreDnsEnforcement = enabled }
    }

    fun setMinimalLauncher(enabled: Boolean) {
        minimalLauncherState.value = enabled
        settingsRepo.update { it.hardcoreMinimalLauncher = enabled }
    }

    // === Service control ===
    fun startService() {
        settingsRepo.update { it.hardcoreServiceEnabled = true }
        // If no manual session and no schedules, activate indefinite manual session
        val now = System.currentTimeMillis()
        val hasManual = settingsRepo.data.hardcoreManualUntilEpochMs > now
        val hasSchedules = repo.getEnabledSchedules().isNotEmpty()
        if (!hasManual && !hasSchedules) {
            settingsRepo.update { it.hardcoreManualUntilEpochMs = Long.MAX_VALUE }
            manualUntilState.value = Long.MAX_VALUE
        }
        HardcoreService.start(application)
    }

    fun stopService() {
        // Toggle off = fully deactivate: clear manual session + stop service
        settingsRepo.update {
            it.hardcoreServiceEnabled = false
            it.hardcoreManualUntilEpochMs = 0
        }
        manualUntilState.value = 0
        HardcoreService.stop(application)
    }

    /** Poke the running service to re-evaluate immediately */
    private fun pokeService() {
        if (HardcoreService.isRunning) {
            HardcoreService.start(application)
        }
    }
}
