package io.beatmaps.cloudflare

import io.beatmaps.util.OUTBOUND_REQUEST_TIMEOUT_MILLIS
import io.beatmaps.util.SMALL_RESPONSE_MAX_BYTES
import io.github.loinguyen.bandwidth.annotations.NetworkDownload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

interface IKVStore {
    @NetworkDownload(
        maxBytes = SMALL_RESPONSE_MAX_BYTES,
        completeTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    )
    suspend fun getKeys(): List<String>

    @NetworkDownload(
        maxBytes = SMALL_RESPONSE_MAX_BYTES,
        completeTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    )
    suspend fun setValue(key: String, value: String)

    @NetworkDownload(
        maxBytes = SMALL_RESPONSE_MAX_BYTES,
        completeTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    )
    suspend fun setValues(kvs: List<KeyValue>)
}

@Serializable
data class KeyResponse(val success: Boolean, val result: List<Key>)

@Serializable
data class Key(val name: String)

@Serializable
data class KeyValue(val key: String, val value: String)

private fun requestCommon(builder: HttpRequestBuilder, authToken: String) {
    builder.header("Authorization", "Bearer $authToken")
    builder.timeout {
        socketTimeoutMillis = 5000
        requestTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    }
}

private class KVStore(val client: HttpClient, val worker: Worker, val namespaceId: String) : IKVStore {
    @Throws(HttpRequestTimeoutException::class)
    override suspend fun getKeys() = client.get("https://api.cloudflare.com/client/v4/accounts/${worker.accountId}/storage/kv/namespaces/$namespaceId/keys") {
        requestCommon(this, worker.authToken)
    }.body<KeyResponse>().result.map { it.name }

    @Throws(HttpRequestTimeoutException::class)
    override suspend fun setValue(key: String, value: String) {
        client.put("https://api.cloudflare.com/client/v4/accounts/${worker.accountId}/storage/kv/namespaces/$namespaceId/values/$key") {
            requestCommon(this, worker.authToken)
            setBody(value)
        }
    }

    @Throws(HttpRequestTimeoutException::class)
    override suspend fun setValues(kvs: List<KeyValue>) {
        client.put("https://api.cloudflare.com/client/v4/accounts/${worker.accountId}/storage/kv/namespaces/$namespaceId/bulk") {
            requestCommon(this, worker.authToken)
            contentType(ContentType.Application.Json)
            setBody(kvs)
        }
    }
}

class Worker(private val client: HttpClient, val accountId: String, val authToken: String) {
    fun getKVStore(namespaceId: String): IKVStore = KVStore(client, this, namespaceId)
}
