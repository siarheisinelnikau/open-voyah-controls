package org.openvoyah.controls.platform.apps

private val leadingCode = Regex("^INSTALL_[A-Z_]{1,60}(?![A-Za-z0-9_])")

/**
 * Android's status message for a package session starts with a code such as `INSTALL_FAILED_VERSION_DOWNGRADE` and
 * continues with text that quotes file paths and package names. Only the code may reach the diagnostic log; a
 * message without one yields nothing.
 */
internal fun installStatusCode(message: String?): String? = message?.let { leadingCode.find(it)?.value }
