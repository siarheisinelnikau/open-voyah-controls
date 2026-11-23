package org.openvoyah.controls.data.frida

/** What `FridaBridge.connect` is doing right now, for the first-run rows. Called on the connecting thread. */
fun interface DeploymentProgress {
    enum class Stage {
        /** Injector version and the root-owned deployment directory. */
        CHECKING,
        /** Restoring visual changes and stopping this project's previous workers. */
        STOPPING,
        /** Comparing one agent file with the copy the device already holds; [file] and its position are given. */
        VERIFYING,
        /** Sending one agent file through the root channel; [file] and its position are given. */
        UPLOADING,
        /** The device already holds this exact file (same SHA-256, owner and mode), so nothing was sent (done). */
        REUSING,
        /** Copied on the device from a file of this deployment that is already in place (done). */
        COPYING,
        /** Pinning the deployment digests in the trust receipt. */
        SEALING,
        /** Starting the supervisors. */
        STARTING,
    }

    fun report(stage: Stage, file: String?, index: Int, count: Int)

    companion object {
        @JvmField val NONE = DeploymentProgress { _, _, _, _ -> }
    }
}
