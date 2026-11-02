package io.github.tellyteam.telly.ui

import android.content.Context
import android.content.pm.PackageManager

/**
 * What telly is running on. A TV (Fire TV, Android TV) is driven with a remote; a phone or tablet
 * by touch. A phone is a touch device too narrow for the TV layout (shortest side under 600 dp),
 * so it gets the tab bar and turns only for the player, as on iPhone.
 */
object DeviceKind {
    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    fun isTouch(context: Context): Boolean = !isTv(context)

    fun isPhone(context: Context): Boolean = isTouch(context) && context.resources.configuration.smallestScreenWidthDp < 600
}
