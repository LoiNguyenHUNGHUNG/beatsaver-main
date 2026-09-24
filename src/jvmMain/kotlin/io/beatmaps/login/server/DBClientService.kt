package io.beatmaps.login.server

import io.beatmaps.common.dbo.OauthClient
import io.beatmaps.common.dbo.OauthClientDao
import io.beatmaps.util.NETWORK_HANDLER_CONCURRENCY
import io.beatmaps.util.modelPostgresOperation
import io.github.loinguyen.bandwidth.annotations.Handler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nl.myndocs.oauth2.client.AuthorizedGrantType
import nl.myndocs.oauth2.client.Client
import nl.myndocs.oauth2.client.ClientService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val clientByIdSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val clientByCredentialsSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)
private val validClientSlots = Semaphore(NETWORK_HANDLER_CONCURRENCY)

object DBClientService : ClientService {
    fun getClient(clientId: String, clientSecret: String? = null) = transaction {
        modelPostgresOperation()
        OauthClient.selectAll().where {
            (OauthClient.clientId eq clientId).let { q ->
                if (clientSecret != null) {
                    q and (OauthClient.secret eq clientSecret)
                } else { q }
            }
        }.firstOrNull()?.let { client -> OauthClientDao.wrapRow(client) }
    }

    @Handler
    override fun clientOf(clientId: String) =
        runBlocking {
            clientByIdSlots.withPermit {
                getClient(clientId)?.let { client -> convertToClient(client) }
            }
        }

    @Handler
    override fun clientOf(clientId: String, clientSecret: String) =
        runBlocking {
            clientByCredentialsSlots.withPermit {
                getClient(clientId, clientSecret)?.let { client -> convertToClient(client) }
            }
        }

    fun convertToClient(client: OauthClientDao) =
        Client(
            client.clientId,
            client.scopes.toSet(),
            client.redirectUrl.toSet(),
            setOf(
                AuthorizedGrantType.AUTHORIZATION_CODE,
                AuthorizedGrantType.REFRESH_TOKEN
            )
        )

    @Handler
    override fun validClient(client: Client, clientSecret: String): Boolean {
        return runBlocking {
            validClientSlots.withPermit {
                transaction {
                    modelPostgresOperation()
                    !OauthClient.selectAll().where {
                        (OauthClient.clientId eq client.clientId) and (OauthClient.secret eq clientSecret)
                    }.empty()
                }
            }
        }
    }
}
