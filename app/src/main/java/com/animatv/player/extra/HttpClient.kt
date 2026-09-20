package com.animatv.player.extra

import android.util.Log
import com.animatv.player.App
import com.animatv.player.R
import okhttp3.*
import java.io.File
import java.util.concurrent.TimeUnit

class HttpClient(private val useCache: Boolean) {

    private val tlsFactory by lazy {
        try {
            TLSSocketFactory().trustAllHttps()
        } catch (e: Exception) {
            Log.e("HttpClient", "TLSSocketFactory init failed: ${e.message}")
            null
        }
    }

    fun create(request: Request): Call {
        val cacheFile = File(App.context.cacheDir, "HttpClient")
        val cacheSize: Long = 10 * 1024 * 1024

        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(PrivateRepoInterceptor())

        try {
            val tls = tlsFactory
            if (tls != null) {
                builder.sslSocketFactory(tls, tls.getTrustManager())
                builder.hostnameVerifier { _, _ -> true }
            }
        } catch (e: Exception) {
            Log.w("HttpClient", "Could not set SSL factory: ${e.message}")
        }

        if (useCache) {
            try {
                builder.cache(Cache(cacheFile, cacheSize))
            } catch (e: Exception) {
                Log.w("HttpClient", "Could not set cache: ${e.message}")
            }
        }

        return try {
            builder.build().newCall(request)
        } catch (e: Exception) {
            Log.e("HttpClient", "Build failed, using default client: ${e.message}")
            OkHttpClient().newCall(request)
        }
    }
}

class PrivateRepoInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("raw.githubusercontent.com")) {
            val token = try { App.context.getString(R.string.gh_token) } catch (e: Exception) { "" }

            val newRequest = request.newBuilder()
                .addHeader("Authorization", "token $token")
                .build()
            return chain.proceed(newRequest)
        }

        return chain.proceed(request)
    }
}
