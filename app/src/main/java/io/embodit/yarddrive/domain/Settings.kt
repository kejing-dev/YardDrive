package io.embodit.yarddrive.domain

/** Everything the settings panel changes (design settings + the four gate-B defaults), with the design defaults. */
data class Settings(
    val zh: Boolean = true,
    val seat: Int = 1,
    val speed: Double = Car.SPEED_DEFAULT,
    val gap: Double = Yard.GAP_DEFAULT,
    val hints: Boolean = true,
    val topView: Boolean = true,
    val poles: Boolean = true,
    val comfort: Boolean = true,
    val mirrorOut: Int = 5,
    val mirrorPitch: Int = -16,
    /** Gate B: "strict" (any mid-way stop fails), default off. */
    val strict: Boolean = false,
    /** Gate B: the translucent coach's hand in demos, default on, can be switched off. */
    val coachHand: Boolean = true,
    val hands: Boolean = false,
    /** Gate B: hand throttle defaults to tap Go / Stop; true = hold a left-hand pinch. */
    val pinchHold: Boolean = false,
    val gazeDot: Boolean = false,
    val volume: Int = 1,
    val muted: Boolean = false,
) {
    val eyeH: Double get() = Car.EYE_HEIGHTS[seat.coerceIn(0, 2)]
}
