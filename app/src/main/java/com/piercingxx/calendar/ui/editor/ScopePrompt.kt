package com.piercingxx.calendar.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.piercingxx.calendar.core.RecurrenceScope
import com.piercingxx.calendar.ui.theme.Body
import com.piercingxx.calendar.ui.theme.EventTitle
import com.piercingxx.calendar.ui.theme.LocalCalendarColors

/**
 * The §6.3 scope prompt. Raised only when a save or delete touches a recurring
 * event — never for a single row (§6.3: the prompt is disorienting there).
 * The three answers map 1:1 onto [RecurrenceScope]; Cancel writes nothing.
 */
@Composable
fun ScopePrompt(
    title: String,
    onScope: (RecurrenceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCalendarColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        textContentColor = colors.text,
        title = {
            Text("repeat?", style = EventTitle, color = colors.text)
        },
        text = {
            Text(
                "this happens more than once - which events change?",
                style = Body,
                color = colors.muted,
            )
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onScope(RecurrenceScope.ThisInstance) }) {
                    Text("This instance", style = Body, color = colors.text)
                }
                TextButton(onClick = { onScope(RecurrenceScope.ThisAndFollowing) }) {
                    Text("This and following", style = Body, color = colors.text)
                }
                TextButton(onClick = { onScope(RecurrenceScope.AllEvents) }) {
                    Text("All events", style = Body, color = colors.text)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = Body, color = colors.muted)
            }
        },
    )
}

/**
 * §10 / §6.3: an unmodelled shape refuses the write and says why — it never
 * guesses. Plain dialog; the editor stays open behind it.
 */
@Composable
fun RefusalDialog(reason: String, onDismiss: () -> Unit) {
    val colors = LocalCalendarColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.graphite,
        textContentColor = colors.text,
        title = {
            Text("not changed", style = EventTitle, color = colors.warn)
        },
        text = {
            Text(reason, style = Body, color = colors.muted)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", style = Body, color = colors.text)
            }
        },
    )
}
