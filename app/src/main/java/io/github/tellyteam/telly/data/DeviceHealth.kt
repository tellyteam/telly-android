package io.github.tellyteam.telly.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/** What the device itself says about its room to work: free storage and memory. */
object DeviceHealth {
    /** Free space where apps keep their data, or null if it can't be read. */
    fun freeBytes(context: Context): Long? = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()

    /** Android's own judgement that memory is short (it's about to close apps). */
    fun lowMemory(context: Context): Boolean = runCatching {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.lowMemory
    }.getOrDefault(false)

    val isFireTv: Boolean get() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
}
