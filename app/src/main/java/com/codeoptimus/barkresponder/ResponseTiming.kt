package com.codeoptimus.barkresponder

/** How long each response plays, so the trigger can hold off new sounds until it ends. */
object ResponseTiming {
    /**
     * [impulseMs] is the Impulse estimate the trigger has always used; Whistle and
     * Gunshot share it.
     */
    fun lengthMs(mode: ResponseMode, impulseMs: Long): Long = when (mode) {
        ResponseMode.MELODY -> 4200L
        else -> impulseMs
    }
}
