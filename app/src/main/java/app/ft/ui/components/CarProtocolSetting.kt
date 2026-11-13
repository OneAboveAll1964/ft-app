package app.ft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ft.R
import app.ft.core.CarConnection
import app.ft.core.CarProtocol

@Composable
internal fun CarProtocolSetting(): CarProtocol {
    val context = LocalContext.current
    val protocol = rememberCarProtocol()
    PickSetting(
        title = stringResource(R.string.car_protocol),
        options = listOf(
            Option("Baidu CarLife"),
            Option(stringResource(R.string.iccoa_title), stringResource(R.string.iccoa_experimental))
        ),
        chosen = CarProtocol.entries.indexOf(protocol),
        about = stringResource(R.string.car_protocol_about),
        onPick = { CarConnection.selectProtocol(context, CarProtocol.entries[it]) }
    )
    return protocol
}
