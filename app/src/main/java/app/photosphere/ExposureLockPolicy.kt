package app.photosphere

/** Camera-thread state. A provisional first exposure becomes permanent only after acceptance. */
internal class ExposureLockPolicy(
    val requested: Boolean,
    val aeSupported: Boolean,
    val awbSupported: Boolean,
) {
    var aeLock = false
        private set
    var awbLock = false
        private set
    var retained = false
        private set
    var fallbackReason: String? = if (requested && !aeSupported && !awbSupported) "unsupported" else null
        private set

    val needsLock: Boolean get() = requested && (aeSupported || awbSupported)

    fun prepare(aeSettled: Boolean, awbSettled: Boolean): Boolean {
        if (!needsLock || retained || aeLock || awbLock) return false
        if (aeSupported && !aeSettled || awbSupported && !awbSettled) return false
        aeLock = aeSupported
        awbLock = awbSupported
        fallbackReason = null
        return true
    }

    fun confirmed(aeApplied: Boolean, awbApplied: Boolean): Boolean = !needsLock ||
        (aeLock || awbLock) && (!aeLock || aeApplied) && (!awbLock || awbApplied)

    /** Keep any confirmed channel, but never claim an ignored or unsettled lock was applied. */
    fun settleTimedOut(aeApplied: Boolean, awbApplied: Boolean): Boolean {
        if (retained || !needsLock) return false
        val oldAe = aeLock; val oldAwb = awbLock
        aeLock = aeLock && aeApplied
        awbLock = awbLock && awbApplied
        fallbackReason = "settle_or_lock_timeout"
        return oldAe != aeLock || oldAwb != awbLock
    }

    fun resolve(accepted: Boolean, aeApplied: Boolean, awbApplied: Boolean): Boolean {
        if (retained) return false
        val oldAe = aeLock; val oldAwb = awbLock
        aeLock = accepted && aeLock && aeApplied
        awbLock = accepted && awbLock && awbApplied
        retained = aeLock || awbLock
        if (accepted && needsLock && !retained && fallbackReason == null) fallbackReason = "not_applied"
        return oldAe != aeLock || oldAwb != awbLock
    }
}
