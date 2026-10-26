package io.github.tellyteam.telly.data.sync

/** The address a QR code carries to link another device: `telly://sync?code=…`. An iPad's camera opens telly with it. */
object SyncLink {
    fun url(code: String): String = "telly://sync?code=$code"
}
