@file:UseSerializers(OptionalPropertySerializer::class)

package io.beatmaps.controllers

import de.nielsfalk.ktor.swagger.Ignore
import de.nielsfalk.ktor.swagger.ModelClass
import io.beatmaps.api.UserDetail
import io.beatmaps.api.user.from
import io.beatmaps.common.OptionalProperty
import io.beatmaps.common.OptionalPropertySerializer
import io.beatmaps.common.db.NowExpression
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.UserDao
import io.beatmaps.common.util.paramInfo
import io.beatmaps.common.util.requireParams
import io.beatmaps.genericPage
import io.beatmaps.login.Session
import io.beatmaps.util.NETWORK_HANDLER_CONCURRENCY
import io.beatmaps.util.modelPostgresOperation
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.serialization.UseSerializers
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private val uploaderControllerRedirectOldGetSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val userControllerDetailGetSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val userControllerRedirectNameGetSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val userControllerUnlinkDiscordPostSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val userControllerUnlinkPatreonPostSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)

@Resource("/uploader")
class UploaderController {
    @Resource("/{key}")
    data class RedirectOld(
        val key: String,
        @Ignore
        val api: UploaderController
    )
}

@Resource("/profile")
class UserController {
    @Resource("/unlink-discord")
    data class UnlinkDiscord(
        @Ignore
        val api: UserController
    )

    @Resource("/unlink-patreon")
    data class UnlinkPatreon(
        @Ignore
        val api: UserController
    )

    @Resource("/{id?}")
    data class Detail(
        @ModelClass(Int::class)
        val id: OptionalProperty<Int>? = OptionalProperty.NotPresent,
        @Ignore
        val api: UserController
    ) {
        init {
            requireParams(
                paramInfo(Detail::id)
            )
        }
    }

    @Resource("/username/{name}")
    data class RedirectName(
        val name: String,
        @Ignore
        val api: UserController
    )
}

@Resource("/alerts")
class AlertController

fun Route.userController() {
    get<UploaderController.RedirectOld> {
        uploaderControllerRedirectOldGetSlots.withPermit {
            transaction {
                modelPostgresOperation()
                User.selectAll().where {
                    User.hash eq it.key
                }.firstOrNull()?.let { UserDao.wrapRow(it) }
            }?.let {
                call.respondRedirect("/profile/${it.id}")
            } ?: run {
                call.respondRedirect("/")
            }
        }
    }

    get<AlertController> {
        if (call.sessions.get<Session>() == null) {
            call.respondRedirect("/login")
        } else {
            genericPage()
        }
    }

    get<UserController.Detail> { req ->
        userControllerDetailGetSlots.withPermit {
            val reqId = req.id?.orNull()
            if (reqId == null && call.sessions.get<Session>() == null) {
                call.respondRedirect("/login")
            } else {
                val userData = reqId?.let {
                    transaction {
                        modelPostgresOperation()
                        User
                            .selectAll()
                            .where {
                                User.id eq reqId and User.active
                            }
                            .limit(1)
                            .firstOrNull()
                            ?.let { u -> UserDetail.from(u) }
                    }
                }

                genericPage(if (reqId == null || userData != null) HttpStatusCode.OK else HttpStatusCode.NotFound) {
                    userData?.let { detail ->
                        meta("og:type", "profile:${detail.name}")
                        meta("og:site_name", "BeatSaver")
                        meta("og:title", detail.name)
                        meta("og:url", detail.profileLink(absolute = true))
                        link(detail.profileLink(absolute = true), "canonical")
                        meta("og:image", detail.avatar)
                        meta("og:description", "${detail.name}'s BeatSaver profile")
                    }
                }
            }
        }
    }

    get<UserController.RedirectName> {
        userControllerRedirectNameGetSlots.withPermit {
            transaction {
                modelPostgresOperation()
                User.selectAll().where {
                    (User.uniqueName eq it.name) and User.active
                }.firstOrNull()?.let { UserDao.wrapRow(it) }
            }?.let {
                call.respondRedirect("/profile/${it.id}")
            } ?: run {
                call.respondRedirect("/")
            }
        }
    }

    post<UserController.UnlinkDiscord> {
        userControllerUnlinkDiscordPostSlots.withPermit {
            val sess = call.sessions.get<Session>()
            if (sess != null) {
                transaction {
                    modelPostgresOperation()
                    User.update({ User.id eq sess.userId }) {
                        it[discordId] = null
                        it[updatedAt] = NowExpression(updatedAt)
                    }
                }
                call.respondRedirect("/profile#account")
            } else {
                call.respondRedirect("/login")
            }
        }
    }

    post<UserController.UnlinkPatreon> {
        userControllerUnlinkPatreonPostSlots.withPermit {
            val sess = call.sessions.get<Session>()
            if (sess != null) {
                transaction {
                    modelPostgresOperation()
                    User.update({ User.id eq sess.userId }) {
                        it[patreonId] = null
                        it[updatedAt] = NowExpression(updatedAt)
                    }
                }
                call.respondRedirect("/profile#account")
            } else {
                call.respondRedirect("/login")
            }
        }
    }
}
