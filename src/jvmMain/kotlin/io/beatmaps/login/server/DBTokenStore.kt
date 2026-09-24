package io.beatmaps.login.server

import io.beatmaps.common.db.upsert
import io.beatmaps.common.dbo.AccessTokenTable
import io.beatmaps.common.dbo.OauthClient
import io.beatmaps.common.dbo.OauthClientDao
import io.beatmaps.common.dbo.RefreshTokenTable
import io.beatmaps.common.dbo.User
import io.beatmaps.common.dbo.UserDao
import io.beatmaps.util.NETWORK_HANDLER_CONCURRENCY
import io.beatmaps.util.modelPostgresOperation
import io.github.loinguyen.bandwidth.annotations.Handler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nl.myndocs.oauth2.identity.Identity
import nl.myndocs.oauth2.identity.TokenInfo
import nl.myndocs.oauth2.token.AccessToken
import nl.myndocs.oauth2.token.CodeToken
import nl.myndocs.oauth2.token.RefreshToken
import nl.myndocs.oauth2.token.TokenStore
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val accessTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val refreshTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val revokeAccessTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val revokeRefreshTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val storeAccessTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val storeRefreshTokenSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val tokenInfoSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)

fun UserDao.toIdentity() =
    Identity(id.value.toString(), mapOf("object" to this))

object DBTokenStore : TokenStore {
    private val codes = mutableMapOf<String, CodeToken>()

    @Handler
    override fun accessToken(token: String) =
        runBlocking {
            accessTokenSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    AccessTokenTable
                        .join(RefreshTokenTable, JoinType.INNER, AccessTokenTable.refreshToken, RefreshTokenTable.id)
                        .join(OauthClient, JoinType.INNER, AccessTokenTable.clientId, OauthClient.clientId)
                        .join(User, JoinType.INNER, AccessTokenTable.userName, User.id)
                        .selectAll()
                        .where {
                            AccessTokenTable.id eq token
                        }
                        .singleOrNull()?.let {
                            OauthClientDao.wrapRow(it)

                            accessTokenFromResult(it)
                        }
                }
            }
        }

    private fun accessTokenFromResult(row: ResultRow) =
        AccessToken(
            row[AccessTokenTable.id].value,
            row[AccessTokenTable.type],
            row[AccessTokenTable.expiration],
            UserDao.wrapRow(row).toIdentity(),
            row[AccessTokenTable.clientId],
            row[AccessTokenTable.scope].split(",").toSet(),
            refreshToken(row)
        )

    override fun codeToken(token: String) =
        codes[token]?.let { code ->
            if (code.expired()) {
                codes.remove(token)
                null
            } else {
                code
            }
        }

    override fun consumeCodeToken(token: String): CodeToken? = codes.remove(token)

    @Handler
    override fun refreshToken(token: String) =
        runBlocking {
            refreshTokenSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    RefreshTokenTable
                        .join(User, JoinType.INNER, RefreshTokenTable.userName, User.id)
                        .selectAll()
                        .where {
                            RefreshTokenTable.id eq token
                        }.singleOrNull()?.let {
                            refreshToken(it)
                        }
                }
            }
        }

    private fun refreshToken(row: ResultRow) = RefreshToken(
        row[RefreshTokenTable.id].value,
        row[RefreshTokenTable.expiration],
        UserDao.wrapRow(row).toIdentity(),
        row[RefreshTokenTable.clientId],
        row[RefreshTokenTable.scope].split(",").toSet()
    )

    @Handler
    override fun revokeAccessToken(token: String) {
        runBlocking {
            revokeAccessTokenSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    AccessTokenTable.deleteWhere {
                        id eq token
                    }
                }
            }
        }
    }

    @Handler
    override fun revokeRefreshToken(token: String) {
        runBlocking {
            revokeRefreshTokenSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    RefreshTokenTable.deleteWhere {
                        id eq token
                    }
                }
            }
        }
    }

    @Handler
    override fun storeAccessToken(accessToken: AccessToken) {
        runBlocking {
            storeAccessTokenSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    AccessTokenTable.insert {
                        it[id] = accessToken.accessToken
                        it[type] = accessToken.tokenType
                        it[expiration] = accessToken.expireTime
                        it[scope] = accessToken.scopes.joinToString(",")
                        it[userName] = accessToken.identity?.username?.toIntOrNull()
                        it[clientId] = accessToken.clientId
                        it[refreshToken] = accessToken.refreshToken?.refreshToken
                    }

                    if (accessToken.refreshToken != null) {
                        storeRefreshTokenLocal(accessToken.refreshToken!!)
                    }
                }
            }
        }
    }

    override fun storeCodeToken(codeToken: CodeToken) {
        // Remove expired codes
        codes.entries.removeAll { it.value.expired() }
        codes[codeToken.codeToken] = codeToken
    }

    @Handler
    override fun storeRefreshToken(refreshToken: RefreshToken) {
        runBlocking {
            storeRefreshTokenSlots.withPermit {
                storeRefreshTokenLocal(refreshToken)
            }
        }
    }

    private fun storeRefreshTokenLocal(refreshToken: RefreshToken) {
        transaction {
            modelPostgresOperation()
            RefreshTokenTable.upsert(RefreshTokenTable.id) {
                it[id] = refreshToken.refreshToken
                it[expiration] = refreshToken.expireTime
                it[scope] = refreshToken.scopes.joinToString(",")
                it[userName] = refreshToken.identity?.username?.toIntOrNull()
                it[clientId] = refreshToken.clientId
            }
        }
    }

    @Handler
    override fun tokenInfo(token: String) =
        runBlocking {
            tokenInfoSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    AccessTokenTable
                        .join(RefreshTokenTable, JoinType.INNER, AccessTokenTable.refreshToken, RefreshTokenTable.id)
                        .join(OauthClient, JoinType.INNER, AccessTokenTable.clientId, OauthClient.clientId)
                        .join(User, JoinType.INNER, AccessTokenTable.userName, User.id)
                        .selectAll()
                        .where {
                            AccessTokenTable.id eq token
                        }
                        .singleOrNull()?.let {
                            val accessToken = accessTokenFromResult(it)

                            TokenInfo(
                                accessToken.identity,
                                DBClientService.convertToClient(OauthClientDao.wrapRow(it)),
                                accessToken.scopes
                            )
                        }
                }
            }
        }

    fun deleteForUser(userId: Int) {
        transaction {
            modelPostgresOperation()
            AccessTokenTable
                .deleteWhere {
                    userName eq userId
                }

            RefreshTokenTable
                .deleteWhere {
                    userName eq userId
                }
        }
    }
}
