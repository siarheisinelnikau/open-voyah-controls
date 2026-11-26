package org.openvoyah.controls.domain.apollo

/** The features to check on the factory page and the exact request-cache keys that can be attributed to them. */
internal enum class ApolloFeature(
    val id: String,
    val settingRequestKey: String? = null,
    val serviceRequestKey: String? = null,
) {
    TLC("tlc", settingRequestKey = "TLC_FUNC_ENABLE"),
    TRAFFIC_LIGHTS("traffic_lights"),
    GREEN_SIGNAL_SOUND("green_signal_sound"),
    TSR("tsr"),
    ISA("isa", serviceRequestKey = "ISA_FUNC_ENABLE_SA"),
    ISLC("islc", settingRequestKey = "ISLC_FUNC_ENABLE", serviceRequestKey = "ISLC_FUNC_ENABLE_SA"),
}

/** These values describe factory requests. They cannot establish a feature's presence, access or current state. */
internal data class ApolloFeatureRequests(val setting: Int?, val service: Int?)

internal fun ApolloFeature.requestsFrom(values: Map<String, Int>): ApolloFeatureRequests =
    ApolloFeatureRequests(settingRequestKey?.let(values::get), serviceRequestKey?.let(values::get))
