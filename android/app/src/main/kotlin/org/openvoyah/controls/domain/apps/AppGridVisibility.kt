package org.openvoyah.controls.domain.apps

/** Why an app starts hidden in All apps until the user chooses otherwise. */
internal enum class DefaultHideReason {
    /** Part of the firmware image, including its internal actions such as a Wi-Fi or climate page. */
    FIRMWARE,
    /** Published by the head unit maker but installed as an ordinary app. */
    MAKER,
    /** A keyboard; its launcher entry only opens the keyboard's setup. */
    KEYBOARD,
    /** A service app such as Google Play services or microG, whose entry only opens its settings. */
    SERVICES,
    /** A second launcher entry of an app that already has its own tile. */
    EXTRA_ENTRY,
}

/** What Android reports about one launcher entry that decides whether it starts hidden. */
internal data class AppGridEntryFacts(
    val component: String,
    val preinstalled: Boolean,
    val inputMethod: Boolean,
    /** The package's own launch activity, or the only entry Android lists for it. */
    val mainEntry: Boolean,
)

/**
 * The default of the per-app **Hide** choice. Firmware apps and their internal actions, keyboards, service apps and
 * extra launcher entries start hidden; everything else starts visible. The user's choice for an app overrides it.
 */
internal object AppGridVisibility {
    private val servicePackages = setOf("com.google.android.gms", "com.mgoogle.android.gms", "org.microg.gms")

    fun defaultHideReason(entry: AppGridEntryFacts): DefaultHideReason? {
        val packageName = entry.component.substringBefore('/')
        return when {
            entry.preinstalled -> DefaultHideReason.FIRMWARE
            HeadUnitMaker.owns(packageName) -> DefaultHideReason.MAKER
            entry.inputMethod -> DefaultHideReason.KEYBOARD
            packageName in servicePackages -> DefaultHideReason.SERVICES
            !entry.mainEntry -> DefaultHideReason.EXTRA_ENTRY
            else -> null
        }
    }
}

/** Vendor namespaces of the head unit maker and its suppliers seen on the reviewed firmware. */
internal object HeadUnitMaker {
    private val packagePrefixes = listOf("com.qinggan.", "com.pateo.", "com.qingos.", "com.mega.")

    fun owns(packageName: String): Boolean = packagePrefixes.any(packageName::startsWith)
}
