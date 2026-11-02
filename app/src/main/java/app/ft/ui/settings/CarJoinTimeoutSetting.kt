package app.ft.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.ft.R
import app.ft.carlife.JoinWatch
import app.ft.ui.components.Option
import app.ft.ui.components.PickSetting
import app.ft.ui.theme.Localized

@Composable
internal fun CarJoinTimeoutSetting(initial: Int, onChange: (Int) -> Unit) {
    var timeout by remember(initial) { mutableIntStateOf(initial) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val preset = JoinWatch.TIMEOUT_SECONDS.indexOf(timeout)
    val options = JoinWatch.TIMEOUT_SECONDS.map { Option(stringResource(R.string.car_join_timeout_seconds, it)) } +
        Option(if (preset < 0) stringResource(R.string.car_join_timeout_custom_value, timeout)
            else stringResource(R.string.car_join_timeout_custom))
    PickSetting(
        stringResource(R.string.car_join_timeout),
        options,
        if (preset < 0) options.lastIndex else preset,
        about = stringResource(R.string.car_join_timeout_about)
    ) { index ->
        if (index == options.lastIndex) {
            editing = true
        } else {
            timeout = JoinWatch.TIMEOUT_SECONDS[index]
            onChange(timeout)
        }
    }
    if (editing) {
        var text by rememberSaveable { mutableStateOf(timeout.toString()) }
        val seconds = text.trim().toIntOrNull()
        val valid = seconds != null && seconds in JoinWatch.MIN_TIMEOUT_SECONDS..JoinWatch.MAX_TIMEOUT_SECONDS
        val focus = remember { FocusRequester() }
        fun save() {
            if (!valid) return
            timeout = seconds
            onChange(seconds)
            editing = false
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Localized { Text(stringResource(R.string.car_join_timeout)) } },
            text = {
                Localized {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.car_join_timeout_input)) },
                        supportingText = {
                            Text(stringResource(R.string.car_join_timeout_range,
                                JoinWatch.MIN_TIMEOUT_SECONDS, JoinWatch.MAX_TIMEOUT_SECONDS))
                        },
                        isError = text.isNotEmpty() && !valid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            },
            confirmButton = {
                Localized {
                    TextButton(onClick = { save() }, enabled = valid) { Text(stringResource(R.string.action_done)) }
                }
            },
            dismissButton = {
                Localized {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        )
    }
}
