package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI


@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        // Needs to clear cookies between sessions to generate new cookies.
        safe {
            // This can throw an exception on unsupported devices :(
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /**
     * Gets the headers with cookies, webview user agent included!
     * */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        val cookies = savedCookies[request.url.host]
        if (cookies != null) {
            // Retry path: continue down this chain. Never retry via
            // app.baseClient.newCall() — this interceptor is installed on
            // baseClient, so that re-entered intercept() forever
            // (intercept -> proceed -> intercept … -> StackOverflowError).
            return@runBlocking proceed(chain, request, cookies)
        }

        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response)) {
            return@runBlocking response
        }
        response.close()
        solveAndProceed(chain, request) ?: run {
            debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
            chain.proceed(request)
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean =
        response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES

    private fun getWebViewCookie(url: String): String? {
        return safe {
            CookieManager.getInstance()?.getCookie(url)
        }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager
     * Also saves the cookies.
     * */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        // Not sure if this takes expiration into account
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    /**
     * Retry [request] with solved cookies by continuing down [chain].
     * Must take the chain: this interceptor lives on app.baseClient, so a
     * fresh baseClient.newCall() would re-enter intercept() infinitely.
     * If the stored cookies are stale (challenged again), drop them, solve
     * once more, and retry a single final time — never loop unboundedly.
     */
    private suspend fun proceed(
        chain: Interceptor.Chain,
        request: Request,
        cookies: Map<String, String>,
        allowResolve: Boolean = true,
    ): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers =
            getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        val response = chain.proceed(
            request.newBuilder()
                .headers(headers)
                .build()
        )
        if (allowResolve && isCloudflareChallenge(response)) {
            response.close()
            savedCookies.remove(request.url.host)
            return solveAndProceed(chain, request) ?: chain.proceed(request)
        }
        return response
    }

    /** WebView-solve for cf_clearance, then proceed exactly once. Null if unsolved. */
    private suspend fun solveAndProceed(
        chain: Interceptor.Chain,
        request: Request,
    ): Response? {
        val cookies = solveCloudflare(request) ?: return null
        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
        return proceed(chain, request, cookies, allowResolve = false)
    }

    /** "pkg/version" of the system WebView, or null when unqueryable/absent. */
    private fun currentWebViewPackage(): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.webkit.WebView.getCurrentWebViewPackage()?.let {
                    "${it.packageName}/${it.versionName}"
                }
            } else null
        } catch (_: Throwable) { null }
    }

    private suspend fun solveCloudflare(request: Request): Map<String, String>? {
        val url = request.url.toString()

        // If no cookies then try to get them
        // Remove this if statement if cookies expire
        if (!trySolveWithSavedCookies(request)) {
            // Fail fast on WebView-less firmware (some TVs): without a WebView
            // provider the solve below would just burn the full timeout.
            // A query failure means "unknown", not "absent" — attempt anyway.
            val webViewAbsent = try {
                android.os.Build.VERSION.SDK_INT >= 26 &&
                    android.webkit.WebView.getCurrentWebViewPackage() == null
            } catch (_: Throwable) { false }
            if (webViewAbsent) {
                Log.w(TAG, "No WebView package on device, skipping Cloudflare solve for ${request.url.host}")
                return null
            }
            Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
            Log.d(TAG, "WebView package: ${currentWebViewPackage() ?: "(unknown: SDK<26 or missing)"}")
            WebViewResolver(
                // Never exit based on url
                Regex(".^"),
                // Cloudflare needs default user agent
                userAgent = null,
                // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
                useOkhttp = false,
                // Match every url for the requestCallBack
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(
                url
            ) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host]
        return cookies
    }
}