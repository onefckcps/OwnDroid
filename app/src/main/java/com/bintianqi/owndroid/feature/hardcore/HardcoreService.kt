package com.bintianqi.owndroid.feature.hardcore

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.admin.IDevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.R
import com.bintianqi.owndroid.utils.MyNotificationChannel
import com.bintianqi.owndroid.utils.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import java.util.Calendar
import java.util.Collections

@RequiresApi(24)
class HardcoreService : Service() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var pollingJob: Job? = null
    private val currentlySuspended = Collections.synchronizedSet(mutableSetOf<String>())
    /** Whether DNS/restrictions have been applied in this activation period */
    @Volatile private var restrictionsApplied = false
    private var lastNotificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = NotificationCompat.Builder(this, MyNotificationChannel.Hardcore.id)
            .setContentTitle(getString(R.string.hardcore_mode))
            .setSmallIcon(R.drawable.block_fill0)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NotificationType.Hardcore.id, notification)

        isRunning = true
        runningState.value = true
        lastNotificationText = ""
        val myApp = application as MyApplication
        val repo = myApp.container.hardcoreRepo
        val settingsRepo = myApp.container.settingsRepo
        val ph = myApp.container.privilegeHelper

        // Crash recovery: restore suspended state from DB
        pollingJob?.cancel()
        pollingJob = coroutineScope.launch {
            currentlySuspended.addAll(repo.getSuspendedByUs())
            // Check if snapshot exists → restrictions were applied before crash
            val snapshots = repo.getAllSnapshots()
            restrictionsApplied = snapshots.isNotEmpty()
            // Release an orphaned launcher override (e.g. DB wiped while active). Only when no
            // snapshot key exists — during an intentional override the key proves it.
            if (!snapshots.containsKey("home_component")) {
                cleanupMinimalLauncher(this@HardcoreService, ph)
            }

            var wasActive = currentlySuspended.isNotEmpty() || restrictionsApplied

            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val cal = Calendar.getInstance()
                    val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
                        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
                        Calendar.SUNDAY -> 7; else -> 1
                    }

                    // Determine if hardcore mode should be active
                    val enabledSchedules = repo.getEnabledSchedules()
                    val manualUntil = settingsRepo.data.hardcoreManualUntilEpochMs
                    val manualActive = manualUntil > now
                    val scheduleActive = isScheduleActive(enabledSchedules, currentMinutes, dayOfWeek)
                    val isActive = manualActive || scheduleActive
                    activeState.value = isActive

                    if (isActive) {
                        // Compute target suspension set
                        val launcherPkgs = getLauncherVisiblePackages()
                        val protected = getProtectedPackages()
                        val allowlist = repo.getAllowlist()
                        val targetSuspend = launcherPkgs - protected - allowlist

                        // Diff against current
                        val toUnsuspend = synchronized(currentlySuspended) { currentlySuspended - targetSuspend }
                        val toSuspend = targetSuspend - synchronized(currentlySuspended) { currentlySuspended.toSet() }

                        // Persist BEFORE DPM calls (crash safety)
                        if (toSuspend.isNotEmpty() || toUnsuspend.isNotEmpty()) {
                            currentlySuspended.removeAll(toUnsuspend)
                            currentlySuspended.addAll(toSuspend)
                            repo.setSuspendedByUs(synchronized(currentlySuspended) { currentlySuspended.toSet() })
                        }

                        if (toUnsuspend.isNotEmpty()) {
                            ph.safeDpmCall { dpm.setPackagesSuspended(dar, toUnsuspend.toTypedArray(), false) }
                        }
                        if (toSuspend.isNotEmpty()) {
                            ph.safeDpmCall { dpm.setPackagesSuspended(dar, toSuspend.toTypedArray(), true) }
                        }

                        // Apply DNS + restrictions once per activation
                        if (!restrictionsApplied) {
                            applyDnsAndRestrictions(repo, ph)
                            restrictionsApplied = true
                        }

                        // Update notification
                        val statusText = if (manualActive && !scheduleActive) {
                            getString(R.string.hardcore_manual_until, formatTime(manualUntil))
                        } else {
                            getString(R.string.hardcore_schedule_active)
                        }
                        updateNotification(statusText)
                    } else if (wasActive) {
                        // Transition active → inactive: restore everything
                        deactivateAll(repo, ph)
                    }

                    wasActive = isActive

                    // Check if service should keep running
                    val hasSchedules = enabledSchedules.isNotEmpty()
                    val hasManualSession = settingsRepo.data.hardcoreManualUntilEpochMs > now
                    val enabledByUser = settingsRepo.data.hardcoreServiceEnabled
                    if (!hasSchedules && !hasManualSession && !enabledByUser) {
                        Log.d(TAG, "No schedules, no manual session, disabled by user — stopping")
                        activeState.value = false
                        stopForeground(true)
                        stopSelf()
                        runningState.value = false
                        isRunning = false
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in hardcore check", e)
                }
                // Adaptive delay: when a manual session is active, wake up right at expiry.
                // Clamped to [1s, POLL_INTERVAL_MS] so we never busy-loop or overshoot.
                val manualUntilNow = settingsRepo.data.hardcoreManualUntilEpochMs
                val nextDelay = if (manualUntilNow > System.currentTimeMillis()) {
                    val remaining = manualUntilNow - System.currentTimeMillis()
                    maxOf(1_000L, minOf(POLL_INTERVAL_MS, remaining))
                } else {
                    POLL_INTERVAL_MS
                }
                delay(nextDelay)
            }
        }

        return START_STICKY
    }

    private fun isScheduleActive(schedules: List<HardcoreSchedule>, currentMinutes: Int, dayOfWeek: Int): Boolean {
        return schedules.any { it.window.contains(currentMinutes, dayOfWeek) }
    }

    private fun getLauncherVisiblePackages(): Set<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    private fun getProtectedPackages(): Set<String> {
        val pkgs = mutableSetOf(packageName) // OwnDroid itself

        // Current default launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName?.let { pkgs.add(it) }
        } catch (_: Exception) { }

        // Default dialer
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            telecom?.defaultDialerPackage?.let { pkgs.add(it) }
        } catch (_: Exception) { }

        // Default SMS app
        try {
            val sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this)
            sms?.let { pkgs.add(it) }
        } catch (_: Exception) { }

        // System essentials
        pkgs.addAll(listOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.phone",
            "com.android.messaging",
            "com.android.emergency",
            "com.android.systemui"
        ))
        return pkgs
    }

    @Suppress("PrivateApi")
    private fun applyDnsAndRestrictions(repo: HardcoreRepository, ph: com.bintianqi.owndroid.PrivilegeHelper) {
        val settingsRepo = (application as MyApplication).container.settingsRepo
        val config = HardcoreConfig(
            dnsHost = settingsRepo.data.hardcoreDnsHost,
            dnsEnforcementEnabled = settingsRepo.data.hardcoreDnsEnforcement
        )
        // addPersistentPreferredActivity requires device owner (or dhizuku) — work-profile-only
        // installs must not take this path even though the setting defaults to true
        val ps = (application as MyApplication).container.privilegeState.value
        val minimalLauncher = settingsRepo.data.hardcoreMinimalLauncher && (ps.device || ps.dhizuku)

        // Never overwrite an existing snapshot (retry after partial apply would capture our own state as baseline)
        if (repo.getAllSnapshots().isNotEmpty()) {
            Log.d(TAG, "Snapshot already exists, skipping")
            return
        }

        // Snapshot current state before modifying
        ph.safeDpmCall {
            val snapshotEntries = mutableMapOf<String, String>()

            // Snapshot Private DNS state
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val mode = dpm.getGlobalPrivateDnsMode(dar)
                    val host = dpm.getGlobalPrivateDnsHost(dar) ?: ""
                    snapshotEntries["private_dns_mode"] = mode.toString()
                    snapshotEntries["private_dns_host"] = host
                } catch (_: Exception) { }
            }

            // Snapshot user restrictions
            val um = getSystemService(Context.USER_SERVICE) as UserManager
            val restrictions = um.userRestrictions
            for (key in RESTRICTION_KEYS) {
                snapshotEntries["restriction_$key"] = if (restrictions.getBoolean(key)) "1" else "0"
            }

            // Snapshot current default home (presence of this key = launcher override was applied).
            // The key is written even when resolveActivity fails, so apply/restore gating never diverges.
            if (minimalLauncher) {
                var homeComponent = ""
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    val ri = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    homeComponent = ri?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: ""
                } catch (_: Exception) { }
                snapshotEntries["home_component"] = homeComponent
            }
            repo.setSnapshotBatch(snapshotEntries)

            // Apply DNS if enabled
            if (config.dnsEnforcementEnabled && Build.VERSION.SDK_INT >= 29) {
                try {
                    val field = DevicePolicyManager::class.java.getDeclaredField("mService")
                    field.isAccessible = true
                    val idpm = field.get(dpm) as IDevicePolicyManager
                    val ret = idpm.setGlobalPrivateDns(
                        dar, DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, config.dnsHost
                    )
                    if (ret != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        Log.w(TAG, "Failed to set private DNS, ret=$ret")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set private DNS", e)
                }
            }

            // Apply restrictions
            for (key in RESTRICTION_KEYS) {
                dpm.addUserRestriction(dar, key)
            }

            // Enforce minimal launcher (snapshot already written above — crash safe)
            if (minimalLauncher) {
                try {
                    // Clear any persistent preferred we set for the old launcher in a previous
                    // restore cycle, so we don't end up with two HOME entries
                    val oldHome = snapshotEntries["home_component"] ?: ""
                    if (oldHome.contains("/")) {
                        ComponentName.unflattenFromString(oldHome)?.let { oldCn ->
                            try {
                                dpm.clearPackagePersistentPreferredActivities(dar, oldCn.packageName)
                            } catch (_: Exception) { }
                        }
                    }
                    val cn = ComponentName(packageName, MinimalLauncherActivity::class.java.name)
                    packageManager.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                    )
                    val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addCategory(Intent.CATEGORY_DEFAULT)
                    }
                    dpm.addPersistentPreferredActivity(dar, homeFilter, cn)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enforce minimal launcher", e)
                }
            }
        }
    }

    private fun restoreDnsAndRestrictions(repo: HardcoreRepository, ph: com.bintianqi.owndroid.PrivilegeHelper) {
        // Read snapshot first so we can pass the previous launcher to the cleanup.
        // Cleanup is unconditional and idempotent: it must also run when the snapshot was wiped
        // mid-session (DB downgrade/reset), otherwise the persistent preferred HOME entry would
        // stay welded with no in-app recovery path.
        val snapshot = repo.getAllSnapshots()
        cleanupMinimalLauncher(this, ph, snapshot["home_component"])

        if (snapshot.isEmpty()) return

        ph.safeDpmCall {
            // Restore Private DNS
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val mode = snapshot["private_dns_mode"]?.toIntOrNull()
                        ?: DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
                    val host = snapshot["private_dns_host"]?.ifEmpty { null }
                    val field = DevicePolicyManager::class.java.getDeclaredField("mService")
                    field.isAccessible = true
                    val idpm = field.get(dpm) as IDevicePolicyManager
                    val ret = idpm.setGlobalPrivateDns(dar, mode, host)
                    if (ret != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        Log.w(TAG, "Failed to restore private DNS, ret=$ret")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore private DNS", e)
                }
            }

            // Restore restrictions: only clear those we set (were 0 before)
            for (key in RESTRICTION_KEYS) {
                val wasSet = snapshot["restriction_$key"] == "1"
                if (!wasSet) {
                    dpm.clearUserRestriction(dar, key)
                }
                // If it was already set before, leave it alone
            }
        }

        repo.clearSnapshot()
    }

    private fun deactivateAll(repo: HardcoreRepository, ph: com.bintianqi.owndroid.PrivilegeHelper) {
        // Unsuspend all
        val snapshot = synchronized(currentlySuspended) { currentlySuspended.toTypedArray() }
        if (snapshot.isNotEmpty()) {
            ph.safeDpmCall { dpm.setPackagesSuspended(dar, snapshot, false) }
        }
        currentlySuspended.clear()
        repo.setSuspendedByUs(emptySet())

        // Restore DNS + restrictions
        restoreDnsAndRestrictions(repo, ph)
        restrictionsApplied = false

        // Clear manual session if expired
        val settingsRepo = (application as MyApplication).container.settingsRepo
        if (settingsRepo.data.hardcoreManualUntilEpochMs in 1..System.currentTimeMillis()) {
            settingsRepo.update { it.hardcoreManualUntilEpochMs = 0 }
        }

        updateNotification(getString(R.string.hardcore_inactive))
    }

    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val notif = NotificationCompat.Builder(this, MyNotificationChannel.Hardcore.id)
            .setContentTitle(getString(R.string.hardcore_mode))
            .setContentText(text)
            .setSmallIcon(R.drawable.block_fill0)
            .setOngoing(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationType.Hardcore.id, notif)
    }

    private fun formatTime(epochMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = epochMs
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        isRunning = false
        runningState.value = false
        activeState.value = false
        lastNotificationText = ""

        // Graceful stop: unsuspend + restore (off main thread, DB may be slow)
        val myApp = application as MyApplication
        val repo = myApp.container.hardcoreRepo
        val ph = myApp.container.privilegeHelper
        try {
            val t = thread { deactivateAll(repo, ph) }
            t.join(5000) // wait max 5s
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deactivate on destroy", e)
        }
    }

    companion object {
        private const val TAG = "HardcoreService"
        private const val POLL_INTERVAL_MS = 60_000L

        val RESTRICTION_KEYS = listOf(
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_INSTALL_APPS
        )

        @Volatile var isRunning = false
            private set
        val runningState = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** Whether hardcore mode is currently actively blocking */
        val activeState = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun start(context: Context) {
            val intent = Intent(context, HardcoreService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HardcoreService::class.java))
        }

        /**
         * Unconditional, idempotent release of the minimal launcher override: clears the persistent
         * preferred HOME entry, disables the component, and re-establishes the previous launcher
         * as persistent preferred so the user is never shown a chooser.
         *
         * No-op when the component is not enabled. The disable runs inside the same privileged
         * scope as the clear — if the scope aborts (e.g. Dhizuku error), the component stays
         * enabled so a later call can retry.
         *
         * @param previousHome Snapshot value "pkg/cls" of the launcher that was the default
         *   before our override. When non-null and valid, it is set as persistent preferred.
         *   Pass null for orphan-cleanup where the snapshot is already gone (system chooser
         *   may appear once in that edge case).
         */
        fun cleanupMinimalLauncher(
            context: Context,
            ph: com.bintianqi.owndroid.PrivilegeHelper,
            previousHome: String? = null
        ) {
            val pm = context.packageManager
            val cn = ComponentName(context.packageName, MinimalLauncherActivity::class.java.name)
            val enabled = try {
                pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read launcher component state", e)
                false
            }
            if (!enabled) return
            ph.safeDpmCall {
                try {
                    dpm.clearPackagePersistentPreferredActivities(dar, context.packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear persistent preferred home", e)
                }
                // Restore the previous launcher as persistent preferred so the system doesn't
                // show a chooser. Skip if the stored component is our own or missing/invalid.
                if (previousHome != null && previousHome.contains("/") &&
                    !previousHome.startsWith(context.packageName + "/")
                ) {
                    try {
                        ComponentName.unflattenFromString(previousHome)?.let { oldCn ->
                            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                addCategory(Intent.CATEGORY_DEFAULT)
                            }
                            dpm.addPersistentPreferredActivity(dar, homeFilter, oldCn)
                            Log.d(TAG, "Restored previous launcher: $previousHome")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore previous launcher as preferred", e)
                    }
                }
                try {
                    pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable minimal launcher", e)
                }
            }
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                if (pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                    Log.w(TAG, "No default home resolves after launcher cleanup; system may show chooser once")
                }
            } catch (_: Exception) { }
        }
    }
}
