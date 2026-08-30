package com.elevium.mobileposteragent.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UsbPairingResult(val runnerToken: String, val hubUrl: String)

class UsbPairingClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun pair(hubUrl: String, code: String, deviceId: String, deviceLabel: String): UsbPairingResult {
        require(hubUrl == "http://127.0.0.1:18082")
        require(code.matches(Regex("^[A-Z2-9]{8,16}$")))
        val payload = JSONObject()
            .put("code", code)
            .put("device_id", deviceId)
            .put("device_label", deviceLabel)
        val request = Request.Builder()
            .url("$hubUrl/devices/pair")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "USB pairing was rejected (${response.code})" }
            val body = JSONObject(response.body?.string().orEmpty())
            val token = body.optString("runner_token").trim()
            val canonicalUrl = body.optString("hub_url").trim()
            require(token.isNotEmpty() && canonicalUrl == hubUrl)
            return UsbPairingResult(token, canonicalUrl)
        }
    }
}
