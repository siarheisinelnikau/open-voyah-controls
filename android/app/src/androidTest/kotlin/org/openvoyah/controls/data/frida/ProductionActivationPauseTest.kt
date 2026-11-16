package org.openvoyah.controls.data.frida

import android.test.AndroidTestCase
import org.openvoyah.controls.R
import org.openvoyah.controls.UiText

/** Activation refuses a local update pause before any firmware or root transport work. */
class ProductionActivationPauseTest : AndroidTestCase() {
    fun testEveryActivationEntryRejectsThePersistedUpdatePause() {
        val preferences = context.getSharedPreferences("bridge", 0)
        val previous = preferences.getString("updatePauseToken", null)
        try {
            check(preferences.edit().putString("updatePauseToken", "a".repeat(32)).commit())
            val root = ProductionRootAccess(context)
            val operations = listOf<() -> Unit>(
                { root.requireActivation() },
                { root.executeActive("exit 99") },
                { root.sealDeployment(emptyMap()) },
            )
            for (operation in operations) {
                try {
                    operation()
                    fail("A paused activation must be refused before reaching the transport")
                } catch (refused: ProductionRootAccess.Refused) {
                    assertEquals(UiText.get(R.string.runtime_update_paused), refused.message)
                }
            }
        } finally {
            check(preferences.edit().putString("updatePauseToken", previous).commit())
        }
    }
}
