package org.openvoyah.controls.domain.vehicle

internal enum class HeadlightSource(val key: String) {
    FACTORY("qinggan"), ANDROID_AUTOMOTIVE("android_automotive"),
}

internal enum class HeadlightInput(val source: HeadlightSource) {
    FACTORY_SELECTOR(HeadlightSource.FACTORY),
    ANDROID_SELECTOR(HeadlightSource.ANDROID_AUTOMOTIVE),
    ANDROID_HEADLAMPS(HeadlightSource.ANDROID_AUTOMOTIVE),
    ANDROID_HIGH_BEAM(HeadlightSource.ANDROID_AUTOMOTIVE),
}

internal enum class HeadlightInputState {
    MODEL_UNREVIEWED,
    SERVICE_ABSENT,
    SERVICE_UNREVIEWED,
    PLATFORM_UNAVAILABLE,
    PERMISSION_DENIED,
    DISCONNECTED,
    PROPERTY_UNAVAILABLE,
    INCOMPATIBLE_PROPERTY,
    SUBSCRIPTION_REJECTED,
    SUBSCRIBED,
    READ_FAILED,
}
