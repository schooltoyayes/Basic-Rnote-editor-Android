package io.github.kjly.brna.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kjly.brna.ui.theme.BrnaColors
import kotlinx.coroutines.delay

/**
 * The Typewriter's text entry. Desktop Rnote types straight onto the canvas; on a tablet
 * the on-screen keyboard would cover half the page anyway, so the text is written here
 * and placed where the canvas was tapped.
 *
 * A tap outside doesn't close it — that would throw away what was typed. Emptying the
 * text and confirming removes the box, as it does in Rnote.
 */
@Composable
fun TextEntryDialog(
    initialText: String,
    isNew: Boolean,
    onConfirm: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (isNew) "New text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text("Type here…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Delete", color = BrnaColors.DestructiveTint) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    LaunchedEffect(Unit) {
        // The dialog's window has to be up before its field can take focus.
        delay(100)
        focus.requestFocus()
        keyboard?.show()
    }
}
