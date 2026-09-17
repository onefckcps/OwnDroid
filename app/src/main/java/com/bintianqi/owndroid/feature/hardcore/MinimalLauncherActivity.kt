package com.bintianqi.owndroid.feature.hardcore

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bintianqi.owndroid.MainActivity
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class LauncherApp(val packageName: String, val label: String)

/** Resolved tap targets for the header (null = greyed out, not clickable) */
private data class HeaderTargets(val clock: Intent?, val calendar: Intent?)

private const val TAG = "MinimalLauncher"

/**
 * Resolve [probe] to the intent to start for it, or null when no app handles it or the
 * resolved package is currently suspended (hardcore) or not allowlisted.
 *
 * The resolved package's own launcher intent is preferred: it is always exported and
 * startable, whereas the probe's resolved activity may not be exported (e.g. some clock
 * apps' SHOW_ALARMS handler) — starting such a raw probe would die with a SecurityException.
 */
private fun resolveTarget(pm: PackageManager, probe: Intent, allowlist: Set<String>): Intent? {
    val ai = pm.resolveActivity(probe, 0)?.activityInfo ?: return null
    if (ai.packageName !in allowlist) return null
    if (runCatching { pm.isPackageSuspended(ai.packageName) }.getOrDefault(false)) return null
    pm.getLaunchIntentForPackage(ai.packageName)?.let { return it }
    return if (ai.exported) probe else null
}

private fun clockProbes(): List<Intent> = buildList {
    if (Build.VERSION.SDK_INT >= 26) {
        // Intent.CATEGORY_APP_CLOCK is missing from the API 37 SDK stubs -> literal
        add(Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CLOCK"))
    }
    add(Intent(AlarmClock.ACTION_SHOW_ALARMS))
}

private fun calendarProbes(): List<Intent> = buildList {
    if (Build.VERSION.SDK_INT >= 26) {
        add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
    }
    add(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI))
}

/**
 * Load the allowlist and resolve header tap targets in one pass. Clock/calendar packages are
 * removed from the list: they are launched via the header instead, and when not allowlisted
 * (suspended during hardcore) they would be dead entries anyway.
 */
private suspend fun loadLauncherData(container: com.bintianqi.owndroid.AppContainer): Pair<List<LauncherApp>, HeaderTargets> =
    withContext(Dispatchers.IO) {
        val pm = container.app.packageManager
        val allowlist = container.hardcoreRepo.getAllowlist().toMutableSet()
        val clockPkg = clockProbes().firstNotNullOfOrNull { resolveTarget(pm, it, allowlist) }?.`package`
        val calendarPkg = calendarProbes().firstNotNullOfOrNull { resolveTarget(pm, it, allowlist) }?.`package`
        allowlist.removeAll(listOfNotNull(clockPkg, calendarPkg))
        val apps = allowlist.mapNotNull { pkg ->
            pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = try {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
            LauncherApp(pkg, label)
        }.sortedBy { it.label.lowercase() }
        apps to HeaderTargets(
            clock = clockPkg?.let(pm::getLaunchIntentForPackage),
            calendar = calendarPkg?.let(pm::getLaunchIntentForPackage)
        )
    }

/**
 * Black, text-only home screen enforced while hardcore mode is active.
 * Enabled/disabled as a component by HardcoreService and locked in via
 * DevicePolicyManager.addPersistentPreferredActivity (device owner only).
 */
class MinimalLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            MinimalLauncherRoot()
        }
    }
}

@Composable
private fun MinimalLauncherRoot() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = (context.applicationContext as MyApplication).container

    // Re-load apps whenever the launcher resumes (e.g. allowlist changed before activation)
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var apps by remember { mutableStateOf(emptyList<LauncherApp>()) }
    var headerTargets by remember { mutableStateOf(HeaderTargets(null, null)) }
    LaunchedEffect(refreshKey) {
        val (loadedApps, targets) = loadLauncherData(container)
        apps = loadedApps
        headerTargets = targets
    }

    // Home screen: back does nothing
    BackHandler { }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        ClockHeader(headerTargets.clock, headerTargets.calendar)
        Spacer(Modifier.height(40.dp))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.packageManager.getLaunchIntentForPackage(app.packageName)
                                ?.let(context::startActivity)
                        }
                        .padding(vertical = 14.dp)
                )
            }
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.DarkGray,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable {
                    context.startActivity(Intent(context, MainActivity::class.java))
                }
                .padding(12.dp)
        )
    }
}

/**
 * Minute-precision clock. State lives here (not in the root) so only this header recomposes
 * per tick; the ticker aligns to the minute boundary and pauses unless RESUMED.
 *
 * Tap targets come from the root loader: time opens the user's clock app, date the calendar
 * app. Targets are re-resolved on ON_RESUME in the root (suspend state flips when hardcore
 * activates/deactivates while this activity sits in the back stack); null targets are greyed
 * out and not clickable.
 */
@Composable
private fun ClockHeader(clockTarget: Intent?, calendarTarget: Intent?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(60_000L - (System.currentTimeMillis() % 60_000L))
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()) }
    val cal = remember { Calendar.getInstance() }
    cal.timeInMillis = nowMs
    Text(
        "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
        style = MaterialTheme.typography.displayLarge,
        color = Color.White,
        modifier = Modifier
            .alpha(if (clockTarget != null) 1f else 0.38f)
            .clickable(enabled = clockTarget != null) {
                clockTarget?.let {
                    runCatching { context.startActivity(it) }
                        .onFailure { e -> Log.w(TAG, "Failed to start clock app", e) }
                }
            }
    )
    Text(
        dateFormat.format(Date(nowMs)),
        style = MaterialTheme.typography.titleLarge,
        color = Color.Gray,
        modifier = Modifier
            .alpha(if (calendarTarget != null) 1f else 0.38f)
            .clickable(enabled = calendarTarget != null) {
                calendarTarget?.let {
                    runCatching { context.startActivity(it) }
                        .onFailure { e -> Log.w(TAG, "Failed to start calendar app", e) }
                }
            }
    )
}
