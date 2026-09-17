package com.bintianqi.owndroid.feature.hardcore

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.admin.IDevicePolicyManager
import android.content.Context
import android.content.Intent
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
            restrictionsApplied = repo.getAllSnapshots().isNotEmpty()

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
                delay(POLL_INTERVAL_MS)
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
        val config = HardcoreConfig(
            dnsHost = (application as MyApplication).container.settingsRepo.data.hardcoreDnsHost,
            dnsEnforcementEnabled = (application as MyApplication).container.settingsRepo.data.hardcoreDnsEnforcement
        )

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
        }
    }

    private fun restoreDnsAndRestrictions(repo: HardcoreRepository, ph: com.bintianqi.owndroid.PrivilegeHelper) {
        val snapshot = repo.getAllSnapshots()
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
    }
}
