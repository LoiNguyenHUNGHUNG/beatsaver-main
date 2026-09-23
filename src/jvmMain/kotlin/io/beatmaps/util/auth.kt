package io.beatmaps.util

import io.beatmaps.api.OauthScope
import io.beatmaps.cloudflare.CaptchaProvider
import io.beatmaps.cloudflare.CaptchaVerifier
import io.beatmaps.cloudflare.SiteVerifyResponse
import io.beatmaps.common.dbo.UserDao
import io.beatmaps.login.Session
import io.beatmaps.login.server.DBTokenStore
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.myndocs.oauth2.token.AccessToken

enum class AuthType {
    None, Session, Oauth
}

private fun sessionFromToken(token: AccessToken) = (token.identity?.metadata?.get("object") as? UserDao)
    ?.let { Session.fromUser(it, oauth2ClientId = token.clientId) }

@BandwidthVariable("Body")
suspend fun <T> ApplicationCall.optionalAuthorization(
    scope: OauthScope? = null,
    @BandwidthEffect("Body") block: suspend ApplicationCall.(AuthType, Session?) -> T
) {
    // Oauth
    checkOauthHeader(scope)?.let(::sessionFromToken)?.also { block(AuthType.Oauth, it) }
        // Session
        ?: sessions.get<Session>()?.also { block(AuthType.Session, it) }
        // Fallback
        ?: run { block(AuthType.None, null) }
}

@BandwidthVariable("Body")
suspend fun <T> RoutingContext.optionalAuthorization(
    scope: OauthScope? = null,
    @BandwidthEffect("Body") block: suspend RoutingContext.(AuthType, Session?) -> T
) {
    val that = this
    call.optionalAuthorization(scope) { a, b ->
        block(that, a, b)
    }
}

@BandwidthVariable("Body")
suspend fun <T> RoutingContext.requireAuthorization(
    scope: OauthScope? = null,
    @BandwidthEffect("Body") block: suspend RoutingContext.(AuthType, Session) -> T
) {
    optionalAuthorization(scope) { type, sess ->
        if (type == AuthType.None || sess == null) {
            call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
        } else {
            block(type, sess)
        }
    }
}

fun ApplicationCall.checkOauthHeader(scope: OauthScope? = null) =
    request.parseAuthorizationHeader().let { authHeader ->
        if (authHeader is HttpAuthHeader.Single) {
            val token = DBTokenStore.accessToken(authHeader.blob)

            when (token?.expired()) {
                true -> DBTokenStore.revokeAccessToken(token.accessToken).let { null }
                false -> {
                    if (token.scopes.contains(scope?.tag)) {
                        token
                    } else { null }
                }
                null -> null
            }
        } else { null }
    }

@BandwidthVariable("Body")
suspend fun <T> RoutingContext.captchaProvider(
    @BandwidthEffect("Body") block: suspend (CaptchaProvider) -> T
): T = block(CaptchaVerifier.provider(call))

@BandwidthVariable("Body", "Error")
suspend fun <T> RoutingContext.requireCaptcha(
    client: HttpClient,
    captcha: String?,
    @BandwidthEffect("Body") block: suspend RoutingContext.() -> T,
    @BandwidthEffect("Error") error: (suspend RoutingContext.(SiteVerifyResponse) -> T)? = null
) =
    captchaProvider { provider ->
        withContext(Dispatchers.IO) {
            CaptchaVerifier.verify(client, provider, captcha ?: "", call.request.origin.remoteHost)
        }.let { result ->
            if (result.success) {
                block()
            } else {
                error?.invoke(this, result) ?: throw BadRequestException("Bad captcha")
            }
        }
    }

@BandwidthVariable("Body")
suspend fun <T> RoutingContext.captchaIfPresent(
    client: HttpClient,
    captcha: String?,
    @BandwidthEffect("Body") block: suspend RoutingContext.() -> T
) =
    if (captcha != null) {
        this.requireCaptcha(client, captcha, block)
    } else {
        block()
    }
