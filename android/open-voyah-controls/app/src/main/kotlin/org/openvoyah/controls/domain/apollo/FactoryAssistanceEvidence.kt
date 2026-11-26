package org.openvoyah.controls.domain.apollo

internal enum class ApolloCacheStatus {
    NOT_REQUESTED, PROVIDER_UNAVAILABLE, ACCESS_DENIED, SOURCE_CHANGED, TIMEOUT, BUSY, READ_FAILED, SAMPLED,
}

/** A diagnostic snapshot is never accepted as controller feedback or as restoration evidence. */
internal data class FactoryAssistanceEvidence(
    val json: String,
    val cacheStatus: ApolloCacheStatus,
    val cachedFields: Int,
    /** The identities the report already holds, as short text a driver can copy into a support request. */
    val supportSummary: String = "",
    val featureRequests: Map<ApolloFeature, ApolloFeatureRequests> = emptyMap(),
)
