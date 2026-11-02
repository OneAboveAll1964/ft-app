package app.ft.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.core.Pcm16Gain
import app.ft.ui.components.Option
import app.ft.ui.components.PickSetting

@Composable
internal fun IccoaMicrophoneGainSetting(initial: Int, onChange: (Int) -> Unit) {
    var gain by remember(initial) { mutableIntStateOf(Pcm16Gain.clamp(initial)) }
    PickSetting(
        title = stringResource(R.string.iccoa_mic_gain),
        options = (Pcm16Gain.MIN..Pcm16Gain.MAX).map {
            Option(stringResource(R.string.iccoa_mic_gain_value, it))
        },
        chosen = gain - Pcm16Gain.MIN,
        about = stringResource(R.string.iccoa_mic_gain_about)
    ) { index ->
        gain = index + Pcm16Gain.MIN
        onChange(gain)
    }
}
