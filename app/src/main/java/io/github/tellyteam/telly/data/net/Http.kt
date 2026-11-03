package io.github.tellyteam.telly.data.net

import io.github.tellyteam.telly.BuildConfig
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Whether plain HTTP is currently allowed; follows the active provider's setting. */
class NetworkPolicy {
    @Volatile
    var allowCleartext: Boolean = false
}

class CleartextBlockedException(host: String) : IOException(
    "Unencrypted HTTP to $host is turned off. If your provider has no HTTPS, allow it in the provider settings.",
)

/**
 * Network interceptor, so it also sees every redirect hop: an HTTPS URL that redirects
 * to plain HTTP is blocked too.
 */
class CleartextGuard(private val policy: NetworkPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        // A debug build's local test relay (-PdevRelay) is plain HTTP; release builds have none.
        val devRelay = BuildConfig.DEV_RELAY.isNotEmpty() && url.toString().startsWith(BuildConfig.DEV_RELAY)
        if (!url.isHttps && !policy.allowCleartext && !devRelay) throw CleartextBlockedException(url.host)
        return chain.proceed(chain.request())
    }
}

object Http {
    val USER_AGENT = "telly/${BuildConfig.VERSION_NAME}"

    fun client(policy: NetworkPolicy): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .addNetworkInterceptor(CleartextGuard(policy))
        .build()
}

/**
 * Blocking GET; call from a background dispatcher. Error messages name only the host,
 * because Xtream URLs carry the username and password.
 */
fun OkHttpClient.fetch(url: HttpUrl): Response {
    val response = newCall(Request.Builder().url(url).build()).execute()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("${url.host} returned HTTP ${response.code}")
    }
    return response
}
