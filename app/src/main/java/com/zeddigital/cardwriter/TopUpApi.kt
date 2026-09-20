package com.zeddigital.cardwriter

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes one record per top-up to MongoDB, via the Atlas Data API - never a raw
 * connection string baked into the app. The Data API key below is scoped (create
 * it in Atlas as insert-only against this one collection) and is injected at
 * build time from a GitHub Actions secret; see .github/workflows/build-apk.yml
 * and README.md. It is never committed to source control.
 *
 * If DATA_API_URL / DATA_API_KEY haven't been configured, calls fail fast with
 * [NotConfigured] instead of attempting a network request.
 */
object TopUpApi {

    private const val TAG = "TopUpApi"
    private val main = Handler(Looper.getMainLooper())

    class NotConfigured : Exception("Database sync is not configured (missing DATA_API_URL/DATA_API_KEY).")

    /**
     * Fire-and-report a top-up record. [onResult] is always called on the main thread.
     *
     * @param name the cardholder name currently on the card, if any
     * @param amountCents the amount just added, in cents
     * @param whenMillis wall-clock time of the top-up
     */
    fun postTopUpAsync(
        name: String?,
        amountCents: Long,
        whenMillis: Long,
        onResult: (Result<Unit>) -> Unit
    ) {
        val url = BuildConfig.DATA_API_URL
        val key = BuildConfig.DATA_API_KEY
        if (url.isBlank() || key.isBlank()) {
            onResult(Result.failure(NotConfigured()))
            return
        }

        Thread {
            try {
                postTopUpBlocking(name, amountCents, whenMillis, url, key)
                main.post { onResult(Result.success(Unit)) }
            } catch (e: Exception) {
                Log.w(TAG, "top-up sync failed", e)
                main.post { onResult(Result.failure(e)) }
            }
        }.start()
    }

    private fun postTopUpBlocking(
        name: String?,
        amountCents: Long,
        whenMillis: Long,
        dataApiUrl: String,
        dataApiKey: String
    ) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(whenMillis))

        val document = JSONObject().apply {
            put("name", name ?: JSONObject.NULL)
            put("topUpAmount", CardFormat.centsToDollars(amountCents).toDouble())
            put("topUpAmountCents", amountCents)
            put("topUpTime", iso)
        }
        val body = JSONObject().apply {
            put("dataSource", BuildConfig.DATA_SOURCE)
            put("database", BuildConfig.DATABASE_NAME)
            put("collection", BuildConfig.COLLECTION_NAME)
            put("document", document)
        }

        // action/insertOne is appended only if the configured URL is the bare
        // Data API base; a full URL (already ending in insertOne) is used as-is.
        val endpoint = if (dataApiUrl.trimEnd('/').endsWith("insertOne")) {
            dataApiUrl
        } else {
            dataApiUrl.trimEnd('/') + "/action/insertOne"
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("api-key", dataApiKey)

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code: $err")
            }
        } finally {
            conn.disconnect()
        }
    }
}
