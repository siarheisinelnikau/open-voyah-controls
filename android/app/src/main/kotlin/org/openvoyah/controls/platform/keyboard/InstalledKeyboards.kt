package org.openvoyah.controls.platform.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toBitmap
import org.openvoyah.controls.data.frida.KeyboardSelection
import org.openvoyah.controls.domain.keyboard.KeyboardOption

/** A keyboard offered on the Keyboard page, with the icon Android shows for it. */
internal data class InstalledKeyboard(val option: KeyboardOption, val icon: Bitmap?)

/**
 * The keyboards the central display's input method service lists as installed. The OEM keyboards are left out:
 * the firmware selects them itself, and the passenger display's own service binds one of them, so they are offered
 * only as the factory keyboard.
 */
internal object InstalledKeyboards {
    private const val ICON_PIXELS = 96

    fun list(context: Context): List<InstalledKeyboard> {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val packages = context.packageManager
        return manager.inputMethodList.asSequence()
            .filter { it.packageName !in KeyboardSelection.FACTORY_PACKAGES && KeyboardSelection.validId(it.id) }
            .map { info ->
                val label = runCatching { info.loadLabel(packages).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: info.packageName
                val icon = runCatching { info.loadIcon(packages).toBitmap(ICON_PIXELS, ICON_PIXELS) }.getOrNull()
                InstalledKeyboard(KeyboardOption(info.id, label, info.packageName), icon)
            }
            .sortedBy { it.option.label.lowercase() }
            .toList()
    }

    /** Labels of every listed keyboard, the OEM ones included, to name what Android bound. */
    fun labels(context: Context): Map<String, String> {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return emptyMap()
        val packages = context.packageManager
        return manager.inputMethodList.associate { info ->
            info.id to (runCatching { info.loadLabel(packages).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: info.packageName)
        }
    }
}
