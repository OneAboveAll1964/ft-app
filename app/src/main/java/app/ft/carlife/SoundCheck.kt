package app.ft.carlife

object SoundCheck {
    const val LOUD = 40
    const val QUIET_MS = 8_000L

    fun blocked(captureRunningMs: Long, msSinceLoud: Long, phonePlaying: Boolean, ftPlaying: Boolean, androidAuto: Boolean, inCall: Boolean): Boolean =
        phonePlaying && !ftPlaying && !androidAuto && !inCall &&
            captureRunningMs >= QUIET_MS && msSinceLoud >= QUIET_MS
}
