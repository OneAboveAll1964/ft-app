package app.ft.ui.cars

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.ft.R
import app.ft.core.CarModel
import app.ft.core.CarProtocol

@get:DrawableRes
internal val CarModel.illustration: Int
    get() = when (this) {
        CarModel.RAV4_XA50 -> R.drawable.car_rav4_xa50
        CarModel.RAV4_XA60 -> R.drawable.car_rav4_xa60
        CarModel.COROLLA -> R.drawable.car_corolla
        CarModel.CAMRY -> R.drawable.car_camry
        CarModel.SUV -> R.drawable.car_suv
        CarModel.SEDAN -> R.drawable.car_sedan
        CarModel.HATCHBACK -> R.drawable.car_hatchback
        CarModel.OTHER -> R.drawable.car_other
    }

@get:StringRes
internal val CarModel.title: Int
    get() = when (this) {
        CarModel.RAV4_XA50 -> R.string.cars_model_rav4_xa50
        CarModel.RAV4_XA60 -> R.string.cars_model_rav4_xa60
        CarModel.COROLLA -> R.string.cars_model_corolla
        CarModel.CAMRY -> R.string.cars_model_camry
        CarModel.SUV -> R.string.cars_model_suv
        CarModel.SEDAN -> R.string.cars_model_sedan
        CarModel.HATCHBACK -> R.string.cars_model_hatchback
        CarModel.OTHER -> R.string.cars_model_other
    }

@get:StringRes
internal val CarProtocol.title: Int
    get() = when (this) {
        CarProtocol.CARLIFE -> R.string.cars_protocol_carlife
        CarProtocol.ICCOA -> R.string.cars_protocol_iccoa
    }

@StringRes
internal fun transportTitle(mode: Int): Int = when (mode) {
    1 -> R.string.cars_transport_direct
    2 -> R.string.cars_transport_usb
    else -> R.string.cars_transport_hotspot
}
