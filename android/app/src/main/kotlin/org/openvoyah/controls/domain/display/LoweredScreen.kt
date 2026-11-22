package org.openvoyah.controls.domain.display

/**
 * The head unit's motorised display can slide down into the dashboard ("lowered"). Android keeps the 1920 x 720
 * display and every window's size; only the top [VISIBLE_HEIGHT_PX] pixels stay in sight, which is the band the
 * factory launcher lays its lowered layout out in. Both displays behave the same.
 */
internal object LoweredScreen {
    const val VISIBLE_HEIGHT_PX = 560

    /** Settings.Global value the launcher agent publishes: "1" lowered, "0" not, absent without an agent. */
    const val SETTING = "org.openvoyah.controls.screenLowered"

    /** The launcher's own switch broadcast; its `type` extra is 1 when lowered. */
    const val LAYOUT_CHANGED = "action.qg.layout.changed"

    /** Pixels of something ending at [bottomOnScreen] that a lowered display hides. */
    fun hiddenBelow(bottomOnScreen: Int): Int = (bottomOnScreen - VISIBLE_HEIGHT_PX).coerceAtLeast(0)

    fun fromSetting(value: String?): Boolean = value == "1"

    fun fromLayoutType(type: Int): Boolean = type == 1
}
