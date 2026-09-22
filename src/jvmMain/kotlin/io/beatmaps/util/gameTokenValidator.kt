package io.beatmaps.util

import io.beatmaps.api.AuthRequest
import io.beatmaps.api.OculusAuthResponse
import io.beatmaps.api.SteamAPIResponse
import io.beatmaps.api.beatsaberAppid
import io.github.loinguyen.bandwidth.annotations.NetworkDownload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException

class GameTokenValidator(private val client: HttpClient) {
    private val authHost = System.getenv("AUTH_HOST") ?: "http://localhost:3030"

    @NetworkDownload(
        maxBytes = SMALL_RESPONSE_MAX_BYTES,
        completeTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    )
    suspend fun steam(steamId: String, proof: String): Boolean {
        val clientId = System.getenv("STEAM_APIKEY") ?: ""
        val data = client.get("https://api.steampowered.com/ISteamUserAuth/AuthenticateUserTicket/v1?key=$clientId&appid=$beatsaberAppid&ticket=$proof") {
            timeout {
                requestTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
            }
        }
            .body<SteamAPIResponse>()
        return !(data.response.params == null || data.response.params.result != "OK" || data.response.params.steamid.toString() != steamId)
    }

    @NetworkDownload(
        maxBytes = SMALL_RESPONSE_MAX_BYTES,
        completeTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
    )
    suspend fun oculus(oculusId: String, proof: String) = try {
        val data = client.post("$authHost/auth/oculus") {
            timeout {
                requestTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
            }
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(oculusId = oculusId, proof = proof))
        }.body<OculusAuthResponse>()
        data.success
    } catch (e: BadRequestException) {
        false
    } catch (e: ClientRequestException) {
        false
    }
}
