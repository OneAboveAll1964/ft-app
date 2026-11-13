package app.ft.iccoa

/**
 * Only one owner-approved car is currently supported by the encrypted trust store.
 * Bluetooth names may be blank and addresses may rotate. Neither is a stable
 * cryptographic identity; the saved OEM server EC key verifies the selected car.
 */
internal object IccoaQuickTrustPolicy {
    const val PRIMARY_TRUST_SCOPE = "owner-approved-primary-car-v1"

    /**
     * An automatically selected peer must match the remembered car metadata.
     * A manual selection may probe QUICK against the pinned server key; a
     * different receiver cannot authenticate without the matching private key.
     */
    fun mayAttemptQuick(remembered: Boolean, explicitlySelected: Boolean,
                        forceCurrentPin: Boolean): Boolean =
        !forceCurrentPin && (remembered || explicitlySelected)
}
