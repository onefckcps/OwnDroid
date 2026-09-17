package com.bintianqi.owndroid.feature.hardcore

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bintianqi.owndroid.R
import com.bintianqi.owndroid.feature.time_blocker.TimeWindow
import com.bintianqi.owndroid.feature.time_blocker.formatMinutes
import com.bintianqi.owndroid.utils.BottomPadding
import com.bintianqi.owndroid.utils.adaptiveInsets
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.channels.Channel
import java.util.Calendar

private val DAY_NAME_RES = listOf(
    R.string.time_blocker_mon, R.string.time_blocker_tue, R.string.time_blocker_wed,
    R.string.time_blocker_thu, R.string.time_blocker_fri, R.string.time_blocker_sat,
    R.string.time_blocker_sun
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardcoreScreen(
    vm: HardcoreViewModel,
    chosenPackage: Channel<String>,
    onChoosePackage: () -> Unit,
    onNavigateUp: () -> Unit
) {
    val allowlist by vm.allowlistState.collectAsState()
    val schedules by vm.schedulesState.collectAsState()
    val running by vm.serviceRunning.collectAsState()
    val active by vm.isActive.collectAsState()
    val manualUntil by vm.manualUntilState.collectAsState()
    val dnsHost by vm.dnsHostState.collectAsState()
    val dnsEnforcement by vm.dnsEnforcementState.collectAsState()

    val manualActive = manualUntil > System.currentTimeMillis()

    var showActivateDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomTimePicker by rememberSaveable { mutableStateOf(false) }
    var showScheduleDialog by rememberSaveable { mutableStateOf(false) }
    var editingScheduleId by rememberSaveable { mutableIntStateOf(-1) } // -1 = new

    // Receive chosen package from AppChooser
    LaunchedEffect(Unit) {
        val pkg = chosenPackage.receive()
        // AppChooser joins multi-select with newlines
        pkg.split("\n").filter { it.isNotBlank() }.forEach { vm.addToAllowlist(it.trim()) }
    }

    val sb = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        Modifier.nestedScroll(sb.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.hardcore_mode)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                scrollBehavior = sb
            )
        },
        contentWindowInsets = adaptiveInsets()
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === Status card ===
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) colorScheme.primaryContainer else colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.hardcore_mode), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(if (active) R.string.hardcore_active else R.string.hardcore_inactive),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                            if (!active && running && schedules.any { it.enabled }) {
                                Text(
                                    stringResource(R.string.hardcore_auto_schedule_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            if (manualActive) {
                                Text(
                                    if (manualUntil == Long.MAX_VALUE) stringResource(R.string.hardcore_manual_indefinite)
                                    else stringResource(R.string.hardcore_manual_until, formatEpochHm(manualUntil)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            } else if (active) {
                                Text(
                                    stringResource(R.string.hardcore_schedule_active),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = running,
                            onCheckedChange = { if (running) vm.stopService() else vm.startService() }
                        )
                    }
                }
            }

            // === Info card ===
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceVariant
                    )
                ) {
                    Row(Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Outlined.Info, null,
                            Modifier.size(20.dp),
                            tint = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.hardcore_self_binding_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // === Manual activation ===
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showActivateDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.hardcore_activate_now))
                            }
                            if (active || manualActive) {
                                OutlinedButton(
                                    onClick = { vm.deactivate() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.hardcore_deactivate))
                                }
                            }
                        }
                    }
                }
            }

            // === Allowlist ===
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.hardcore_allowlist), style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = onChoosePackage,
                        enabled = !active
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.hardcore_add_app))
                    }
                }
            }
            if (active) {
                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(
                            stringResource(R.string.hardcore_allowlist_frozen),
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            if (allowlist.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.hardcore_no_apps),
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            items(allowlist.toList(), key = { it }) { pkg ->
                AllowlistAppItem(
                    packageName = pkg,
                    removable = !active,
                    onRemove = { vm.removeFromAllowlist(pkg) }
                )
            }

            // === Schedules ===
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    Arrangement.SpaceBetween, Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.hardcore_schedules), style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = {
                            editingScheduleId = -1
                            showScheduleDialog = true
                        },
                        enabled = !active
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.hardcore_add_schedule))
                    }
                }
            }
            if (schedules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.hardcore_no_schedules),
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            items(schedules, key = { it.id }) { schedule ->
                ScheduleItem(
                    schedule = schedule,
                    enabled = !active,
                    onToggle = { vm.toggleScheduleEnabled(schedule) },
                    onEdit = {
                        editingScheduleId = schedule.id
                        showScheduleDialog = true
                    },
                    onDelete = { vm.deleteSchedule(schedule.id) }
                )
            }

            // === DNS ===
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = dnsHost,
                            onValueChange = { vm.setDnsHost(it.trim()) },
                            label = {
                                Text(stringResource(R.string.hardcore_dns_host))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.hardcore_dns_enforcement), style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = dnsEnforcement,
                                onCheckedChange = { vm.setDnsEnforcement(it) }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(BottomPadding + 80.dp)) }
        }
    }

    // === Activate dialog: duration choices ===
    if (showActivateDialog) {
        AlertDialog(
            onDismissRequest = { showActivateDialog = false },
            title = { Text(stringResource(R.string.hardcore_activate_now)) },
            text = {
                Column {
                    TextButton(onClick = {
                        vm.activateManually(1L * 60 * 60 * 1000)
                        showActivateDialog = false
                    }) { Text(stringResource(R.string.hardcore_1_hour)) }
                    TextButton(onClick = {
                        vm.activateManually(2L * 60 * 60 * 1000)
                        showActivateDialog = false
                    }) { Text(stringResource(R.string.hardcore_2_hours)) }
                    TextButton(onClick = {
                        vm.activateManually(4L * 60 * 60 * 1000)
                        showActivateDialog = false
                    }) { Text(stringResource(R.string.hardcore_4_hours)) }
                    TextButton(onClick = {
                        vm.activateManuallyUntil(nextSixAmEpoch())
                        showActivateDialog = false
                    }) { Text(stringResource(R.string.hardcore_until_6am)) }
                    TextButton(onClick = {
                        showActivateDialog = false
                        showCustomTimePicker = true
                    }) { Text(stringResource(R.string.hardcore_custom_time)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActivateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // === Custom end-time picker ===
    if (showCustomTimePicker) {
        val now = Calendar.getInstance()
        val tps = rememberTimePickerState(
            initialHour = now.get(Calendar.HOUR_OF_DAY),
            initialMinute = now.get(Calendar.MINUTE)
        )
        AlertDialog(
            onDismissRequest = { showCustomTimePicker = false },
            title = { Text(stringResource(R.string.hardcore_custom_time)) },
            text = { TimePicker(tps) },
            confirmButton = {
                TextButton(onClick = {
                    vm.activateManuallyUntil(todayAtOrTomorrow(tps.hour, tps.minute))
                    showCustomTimePicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomTimePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // === Schedule add/edit dialog ===
    if (showScheduleDialog) {
        val existing = if (editingScheduleId >= 0) schedules.find { it.id == editingScheduleId } else null
        ScheduleDialog(
            existing = existing,
            onDismiss = { showScheduleDialog = false },
            onConfirm = { schedule ->
                if (existing == null) vm.addSchedule(schedule) else vm.updateSchedule(schedule)
                showScheduleDialog = false
            }
        )
    }
}

@Composable
private fun AllowlistAppItem(
    packageName: String,
    removable: Boolean,
    onRemove: () -> Unit
) {
    val pm = LocalContext.current.packageManager
    val (appLabel, appIcon) = remember(packageName) {
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        (info?.loadLabel(pm)?.toString() ?: packageName) to info?.loadIcon(pm)
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIcon != null) {
                Image(
                    rememberDrawablePainter(appIcon), null,
                    Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(appLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            if (removable) {
                IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(R.drawable.delete_fill0),
                        contentDescription = stringResource(R.string.hardcore_remove_app)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleItem(
    schedule: HardcoreSchedule,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = enabled, onClick = onEdit)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatMinutes(schedule.window.startMinutes)} – ${formatMinutes(schedule.window.endMinutes)}",
                    style = MaterialTheme.typography.titleMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    schedule.window.daysOfWeek.sorted().forEach { day ->
                        Text(
                            stringResource(DAY_NAME_RES.getOrElse(day - 1) { R.string.time_blocker_mon }),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.delete_fill0),
                    contentDescription = stringResource(R.string.hardcore_delete_schedule)
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = { onToggle() },
                enabled = enabled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(
    existing: HardcoreSchedule?,
    onDismiss: () -> Unit,
    onConfirm: (HardcoreSchedule) -> Unit
) {
    var startMinutes by rememberSaveable { mutableIntStateOf(existing?.window?.startMinutes ?: 22 * 60) }
    var endMinutes by rememberSaveable { mutableIntStateOf(existing?.window?.endMinutes ?: 7 * 60) }
    val days = remember {
        (existing?.window?.daysOfWeek ?: (1..7).toSet()).toMutableStateList()
    }
    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.hardcore_add_schedule else R.string.hardcore_edit_schedule))
        },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Start: ${formatMinutes(startMinutes)}") }
                    OutlinedButton(
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("End: ${formatMinutes(endMinutes)}") }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in days,
                            onClick = {
                                if (day in days) days.remove(day) else days.add(day)
                            },
                            label = { Text(stringResource(DAY_NAME_RES[day - 1])) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        HardcoreSchedule(
                            id = existing?.id ?: 0,
                            window = TimeWindow(
                                startMinutes = startMinutes,
                                endMinutes = endMinutes,
                                daysOfWeek = days.toSet()
                            ),
                            enabled = existing?.enabled ?: true
                        )
                    )
                },
                enabled = days.isNotEmpty()
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    if (showStartPicker) {
        val tps = rememberTimePickerState(
            initialHour = startMinutes / 60,
            initialMinute = startMinutes % 60
        )
        AlertDialog(
            onDismissRequest = { showStartPicker = false },
            title = { Text("Start") },
            text = { TimePicker(tps) },
            confirmButton = {
                TextButton(onClick = {
                    startMinutes = tps.hour * 60 + tps.minute
                    showStartPicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showEndPicker) {
        val tpe = rememberTimePickerState(
            initialHour = endMinutes / 60,
            initialMinute = endMinutes % 60
        )
        AlertDialog(
            onDismissRequest = { showEndPicker = false },
            title = { Text("End") },
            text = { TimePicker(tpe) },
            confirmButton = {
                TextButton(onClick = {
                    endMinutes = tpe.hour * 60 + tpe.minute
                    showEndPicker = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** Format epoch ms as HH:MM (local time). */
private fun formatEpochHm(epochMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** Next 06:00 local time as epoch ms (today if still upcoming, else tomorrow). */
private fun nextSixAmEpoch(): Long {
    val cal = Calendar.getInstance()
    val today6am = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return if (today6am.timeInMillis > cal.timeInMillis) {
        today6am.timeInMillis // today 06:00 is still in the future
    } else {
        today6am.add(Calendar.DAY_OF_YEAR, 1)
        today6am.timeInMillis // tomorrow 06:00
    }
}

/** Today at hour:minute, or tomorrow if that time already passed. */
private fun todayAtOrTomorrow(hour: Int, minute: Int): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= System.currentTimeMillis()) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}
