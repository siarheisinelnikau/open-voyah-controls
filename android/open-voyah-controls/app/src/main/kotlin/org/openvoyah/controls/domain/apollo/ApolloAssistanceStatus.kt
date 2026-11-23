package org.openvoyah.controls.domain.apollo

/**
 * What the factory assistance agent said it could apply in the factory settings app. The agent publishes the
 * document itself, so parsing needs no Android types and the document carries no vehicle or account data.
 * An applied part is an answer the factory page was given, and a requested part is a call the factory software
 * accepted; neither is a state the vehicle confirmed. The request id ties a report to the config it answered.
 */
internal data class ApolloAssistanceStatus(
    val reported: Boolean,
    val applied: Set<String>,
    val unavailable: Set<String>,
    val requested: Set<String> = emptySet(),
    val uncertain: Set<String> = emptySet(),
    val partial: Set<String> = emptySet(),
    val request: String? = null,
) {
    /** Whether this report answers the config Controls most recently published. A stale report answers nothing. */
    fun isCurrent(request: String?): Boolean = reported && request != null && request == this.request

    /** True while the driver wants a part and no report for the current config has arrived yet. */
    fun waitingFor(wanted: Map<String, Boolean>, request: String?): Boolean =
        PARTS.any { wanted[it] == true } && !isCurrent(request)

    companion object {
        val PARTS = listOf("subscription", "controller", "equipment")
        val Unreported = ApolloAssistanceStatus(false, emptySet(), emptySet())
        private val REQUEST = Regex("\"request\":\"([0-9a-f]{32})\"")

        /** Reads the agent's own document. Anything unreadable counts as no report yet. */
        fun parse(raw: String?): ApolloAssistanceStatus {
            if (raw.isNullOrBlank() || !raw.contains("\"schema\":1")) return Unreported
            val request = REQUEST.find(raw)?.groupValues?.get(1)
            val applied = PARTS.filter { raw.contains("\"$it\":\"applied\"") }.toSet()
            val unavailable = PARTS.filter { raw.contains("\"$it\":\"unavailable\"") }.toSet()
            val requested = PARTS.filter { raw.contains("\"$it\":\"requested\"") }.toSet()
            val uncertain = PARTS.filter { raw.contains("\"$it\":\"uncertain\"") }.toSet()
            val partial = PARTS.filter { raw.contains("\"$it\":\"partial\"") }.toSet()
            return ApolloAssistanceStatus(true, applied, unavailable, requested, uncertain, partial, request)
        }
    }
}
