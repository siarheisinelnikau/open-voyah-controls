package org.openvoyah.controls.platform.phone

import android.app.Activity
import android.content.Intent
import android.net.Uri

internal fun openPhoneDialer(activity: Activity, number: String) {
    activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
}
