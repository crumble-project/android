package dev.crumble.android.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.crumble.android.model.MAX_CRON_CHARS
import dev.crumble.android.model.Recurrence
import dev.crumble.android.model.TaskDraft
import dev.crumble.android.model.TaskWhen
import dev.crumble.android.model.WEEK
import dev.crumble.android.model.choice
import dev.crumble.android.model.choosing
import dev.crumble.android.model.inTimezone
import dev.crumble.android.model.minuteChoices
import dev.crumble.android.model.recurrenceOf
import dev.crumble.android.model.recurring
import dev.crumble.android.model.runningAt
import dev.crumble.android.model.timezoneChoices
import dev.crumble.android.model.wallClock
import dev.crumble.android.model.weekdayName
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle

private val MONTH_DAYS = (1..31).toList()

@Composable
internal fun TaskTimeFields(draft: TaskDraft, onChange: (TaskDraft) -> Unit) {
    val choice = draft.choice
    val recurrence = recurrenceOf(draft.cron)
    Choice(
        label = "When",
        value = choice.label,
        options = TaskWhen.entries,
        optionLabel = { it.label },
        onPick = { onChange(draft.choosing(it)) },
    )
    when {
        choice == TaskWhen.Once -> RunDateFields(draft, onChange)
        choice == TaskWhen.Custom || recurrence == null -> CronField(draft, onChange)
        else -> RecurrenceFields(recurrence) { onChange(draft.recurring(it)) }
    }
    TimezoneField(draft.timezone) { onChange(draft.inTimezone(it)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, value: String, options: List<T>, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    GroupedRow(title = label, subtitle = value, shape = groupedShape(0, 1), onClick = onClick)
}

@Composable
private fun RunDateFields(draft: TaskDraft, onChange: (TaskDraft) -> Unit) {
    val local = draft.wallClock
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    PickerRow("Date", local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) { pickingDate = true }
    PickerRow("Time", timeText(local.toLocalTime())) { pickingTime = true }
    if (pickingDate) {
        RunDatePicker(local.toLocalDate(), onDismiss = { pickingDate = false }) { onChange(draft.runningAt(LocalDateTime.of(it, local.toLocalTime()))) }
    }
    if (pickingTime) {
        ClockPicker(local.toLocalTime(), onDismiss = { pickingTime = false }) { onChange(draft.runningAt(LocalDateTime.of(local.toLocalDate(), it))) }
    }
}

private fun timeText(time: LocalTime) = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material picker reads and writes midnight UTC, whatever zone the task runs in.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockPicker(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(LocalTime.of(state.hour, state.minute))
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecurrenceFields(recurrence: Recurrence, onChange: (Recurrence) -> Unit) {
    if (recurrence.kind == TaskWhen.Hourly) {
        Choice(
            label = "Minute",
            value = ":%02d".format(recurrence.minute),
            options = minuteChoices(recurrence.minute),
            optionLabel = { ":%02d".format(it) },
            onPick = { onChange(recurrence.copy(minute = it)) },
        )
        return
    }
    if (recurrence.kind == TaskWhen.Weekly) WeekdayToggles(recurrence.days) { onChange(recurrence.copy(days = it)) }
    if (recurrence.kind == TaskWhen.Monthly) {
        Choice(
            label = "Day of the month",
            value = recurrence.day.toString(),
            options = MONTH_DAYS,
            optionLabel = { it.toString() },
            onPick = { onChange(recurrence.copy(day = it)) },
        )
    }
    var picking by remember { mutableStateOf(false) }
    val time = LocalTime.of(recurrence.hour, recurrence.minute)
    PickerRow("Time", timeText(time)) { picking = true }
    if (picking) ClockPicker(time, onDismiss = { picking = false }) { onChange(recurrence.copy(hour = it.hour, minute = it.minute)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayToggles(days: List<Int>, onChange: (List<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (day in WEEK) {
            val on = day in days
            FilterChip(
                selected = on,
                enabled = !(on && days.size == 1),
                onClick = { onChange(if (on) days - day else days + day) },
                label = { Text(weekdayName(day, DateTextStyle.SHORT)) },
            )
        }
    }
}

@Composable
private fun CronField(draft: TaskDraft, onChange: (TaskDraft) -> Unit) {
    OutlinedTextField(
        value = draft.cron,
        onValueChange = { onChange(draft.copy(cron = it.take(MAX_CRON_CHARS))) },
        label = { Text("Cron expression") },
        placeholder = { Text("0 9 * * 1-5") },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TimezoneField(timezone: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    PickerRow("Time zone", timezone) { picking = true }
    if (picking) TimezoneDialog(timezone, onDismiss = { picking = false }, onPick = onPick)
}

@Composable
private fun TimezoneDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val zones = remember(query, current) { timezoneChoices(query, current) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = pageColor) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Time zone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    itemsIndexed(zones, key = { _, zone -> zone }) { index, zone ->
                        GroupedItem(
                            shape = groupedShape(index, zones.size),
                            onClick = {
                                onPick(zone)
                                onDismiss()
                            },
                        ) {
                            RowTitle(zone, maxLines = 1, fontWeight = if (zone == current) FontWeight.SemiBold else null)
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}
