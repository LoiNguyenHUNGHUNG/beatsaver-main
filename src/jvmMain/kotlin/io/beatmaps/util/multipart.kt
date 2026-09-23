package io.beatmaps.util

import io.beatmaps.cloudflare.CaptchaVerifier
import io.beatmaps.common.json
import io.beatmaps.controllers.UploadException
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
import io.ktor.client.HttpClient
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

data class MultipartRequest<U>(val dataMap: Map<String, JsonElement> = emptyMap(), private val recaptchaSuccess: Boolean = false, val fileOutput: U? = null) {
    inline fun <reified T> get() = json.decodeFromJsonElement<T>(JsonObject(dataMap))
    fun validRecaptcha(authType: AuthType) = authType == AuthType.Oauth || recaptchaSuccess
}

@BandwidthEffect(rMaxBytesPerSecond = SMALL_RESPONSE_RATE_BYTES_PER_SECOND, nMax = 1)
private suspend fun MultiPartData.readModeledPart() = readPart()

@BandwidthVariable("Body")
private suspend fun <U> handleMultipartInternal(
    data: MultiPartData,
    ctx: RoutingContext,
    client: HttpClient,
    @BandwidthEffect("Body") cb: suspend (PartData.FileItem) -> U
): MultipartRequest<U> {
    var dataMap = emptyMap<String, JsonElement>()
    var recaptchaSuccess = false
    var fileOutput: U? = null
    var hasFileOutput = false

    while (true) {
        when (val part = data.readModeledPart()) {
            is PartData.FormItem -> {
                // Process recaptcha immediately as it is time-critical
                if (part.name == "recaptcha") {
                    recaptchaSuccess = ctx.captchaProvider { provider ->
                        val verifyResponse = withContext(Dispatchers.IO) {
                            CaptchaVerifier.verify(client, provider, part.value, ctx.call.request.origin.remoteHost)
                        }

                        verifyResponse.success || throw UploadException("Could not verify user [${verifyResponse.errorCodes.joinToString(", ")}]")
                    }
                } else {
                    val newData = try {
                        if (part.value.startsWith("{") && part.value.endsWith("}")) {
                            json.parseToJsonElement(part.value)
                        } else {
                            null
                        }
                    } catch (e: SerializationException) {
                        null
                    } ?: JsonPrimitive(part.value)

                    // The recursive implementation kept the first value for a
                    // repeated field name; preserve that behavior here.
                    val partName = part.name.toString()
                    if (partName !in dataMap) {
                        dataMap = dataMap.plus(partName to newData)
                    }
                }
            }

            is PartData.FileItem -> {
                val output = cb(part)
                if (!hasFileOutput) {
                    fileOutput = output
                    hasFileOutput = true
                }
            }

            null -> return MultipartRequest(dataMap, recaptchaSuccess, fileOutput)
            else -> Unit
        }
    }
}

@BandwidthVariable("Body")
suspend fun <U> RoutingContext.handleMultipart(
    client: HttpClient,
    limit: Long = -1L,
    @BandwidthEffect("Body") cb: suspend (PartData.FileItem) -> U
) =
    handleMultipartInternal(call.receiveMultipart(limit), this, client, cb)
