package org.openvoyah.controls.domain.apollo

/**
 * What the factory assistance agent said it could apply in the factory settings app. The agent publishes the
 * document itself, so parsing needs no Android types and the document carries no vehicle or account data.
 * An applied part is an answer the factory page was given, never a state the vehicle confirmed.
 */
internal data class ApolloAssistanceStatus(
    val reported: Boolean,
    val applied: Set<String>,
    val unavailable: Set<String>,
) {
    /** True while the driver wants a part the installed software did not offer. */
    fun waitingFor(wanted: Map<String, Boolean>): Boolean =
        !reported && ApolloAssistanceStatus.PARTS.any { wanted[it] == true }

    companion object {
        val PARTS = listOf("subscription", "controller", "equipment")
        val Unreported = ApolloAssistanceStatus(false, emptySet(), emptySet())

        /** Reads the agent's own document. Anything unreadable counts as no report yet. */
        fun parse(raw: String?): ApolloAssistanceStatus {
            if (raw.isNullOrBlank() || !raw.contains("\"schema\":1")) return Unreported
            val applied = PARTS.filter { raw.contains("\"$it\":\"applied\"") }.toSet()
            val unavailable = PARTS.filter { raw.contains("\"$it\":\"unavailable\"") }.toSet()
            return ApolloAssistanceStatus(true, applied, unavailable)
        }
    }
}
