package org.omarchy.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36"
const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
const val APP_UA = "OmarchyMusic/2.0 (personal music player)"

val HTTP: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

class HttpError(val code: Int, val body: String, url: String) : IOException("HTTP $code from ${url.substringBefore('?')}: ${body.take(160)}")

fun form(vararg pairs: Pair<String, String>): RequestBody =
    FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()

fun jsonBody(e: JsonElement): RequestBody = e.toString().toRequestBody("application/json".toMediaType())

/** One request; the body as text. Errors (4xx/5xx) raise HttpError with the service's message. */
fun http(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, method: String? = null): String {
    val req = Request.Builder().url(url).header("User-Agent", UA).apply {
        headers.forEach { (k, v) -> header(k, v) }
        method(method ?: if (body != null) "POST" else "GET", body ?: if (method in setOf("POST", "PUT", "DELETE")) ByteArray(0).toRequestBody() else null)
    }.build()
    HTTP.newCall(req).execute().use { r ->
        val text = r.body?.string() ?: ""
        if (!r.isSuccessful) throw HttpError(r.code, text, url)
        return text
    }
}

fun httpJson(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, method: String? = null): JsonElement {
    val text = http(url, headers, body, method)
    return if (text.isBlank()) JsonObject(emptyMap()) else JSON.parseToJsonElement(text)
}

fun httpBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
    val req = Request.Builder().url(url).header("User-Agent", UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    HTTP.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw HttpError(r.code, "", url)
        return r.body!!.bytes()
    }
}

fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

// ---- reading JSON without a class per answer ----

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var e = this
    for (k in keys) e = if (k is Int) e[k] else e[k as String]
    return e
}
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.content
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
val JsonElement?.list: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: JsonObject? get() = this as? JsonObject
