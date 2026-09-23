package io.beatmaps.util

import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
import io.ktor.server.application.Application
import io.ktor.server.application.Plugin
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.FormAuthenticationProvider
import io.ktor.server.auth.form

/**
 * Effect-polymorphic view of Ktor's invoke-once plugin configuration boundary.
 *
 * Ktor executes [configure] while installing [plugin]. The checker therefore
 * substitutes the callback's effect for `Config` at each call site.
 */
@BandwidthVariable("Config")
@BandwidthEffect("Config")
fun <B : Any, F : Any> Application.installWithBandwidthEffect(
    plugin: Plugin<Application, B, F>,
    @BandwidthEffect("Config") configure: B.() -> Unit
): F = install(plugin, configure)

/** Effect-polymorphic view of Ktor's invoke-once form configuration block. */
@BandwidthVariable("Config")
@BandwidthEffect("Config")
fun AuthenticationConfig.formWithBandwidthEffect(
    name: String?,
    @BandwidthEffect("Config") configure: FormAuthenticationProvider.Config.() -> Unit
) = form(name, configure)
