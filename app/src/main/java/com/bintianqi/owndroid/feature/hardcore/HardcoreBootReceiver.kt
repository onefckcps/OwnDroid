package com.bintianqi.owndroid.feature.hardcore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bintianqi.owndroid.MyApplication
import kotlin.concurrent.thread

class HardcoreBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT < 24) return
        val pendingResult = goAsync()
        thread {
            try {
                val myApp = context.applicationContext as MyApplication
                val repo = myApp.container.hardcoreRepo
                val serviceEnabled = myApp.container.settingsRepo.data.hardcoreServiceEnabled
                val manualActive = myApp.container.settingsRepo.data.hardcoreManualUntilEpochMs > System.currentTimeMillis()
                if (serviceEnabled && (repo.getEnabledSchedules().isNotEmpty() || manualActive)) {
                    HardcoreService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
