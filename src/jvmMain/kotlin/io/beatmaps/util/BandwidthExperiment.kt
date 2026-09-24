package io.beatmaps.util

import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

/** Complete-call timeout used by the bounded-response experiment. */
const val OUTBOUND_REQUEST_TIMEOUT_MILLIS: Long = 20_000

/**
 * Maximum unprofiled JSON or acknowledgement response admitted by the experiment.
 *
 * Authenticated API responses are workload assumptions rather than measured samples
 * because profiling them requires service credentials.
 */
const val SMALL_RESPONSE_MAX_BYTES: Long = 16 * 1_024

/**
 * Maximum score response admitted by the profiled workload.
 *
 * The September 23, 2026 profile observed at most 6,894 bytes. The 7 KiB ceiling
 * is 274 bytes, or about four percent, above that observed maximum.
 */
const val SCORE_RESPONSE_MAX_BYTES: Long = 7 * 1_024

/**
 * Exact avatar size for the fixed BeatSaver playlist-account fixture.
 *
 * The experiment uses BeatSaver user 58338 (Joetastic) and must fail rather than
 * silently substituting a different or larger avatar response.
 */
const val PLAYLIST_AVATAR_MAX_BYTES: Long = 26_482

/**
 * Exact avatar size for the fixed public Discord fixture.
 *
 * The experiment uses Discord user 500064999524401152 with avatar hash
 * 26353295679abfb6d36c20ba18695bdb and must fail if that fixture changes.
 */
const val DISCORD_AVATAR_MAX_BYTES: Long = 40_234

/** Existing complete-call timeout for avatar downloads. */
const val IMAGE_REQUEST_TIMEOUT_MILLIS: Long = 60_000

/** Rate implied by [SMALL_RESPONSE_MAX_BYTES] and [OUTBOUND_REQUEST_TIMEOUT_MILLIS]. */
const val SMALL_RESPONSE_RATE_BYTES_PER_SECOND: Long =
    (SMALL_RESPONSE_MAX_BYTES * 1_000 + OUTBOUND_REQUEST_TIMEOUT_MILLIS - 1) /
        OUTBOUND_REQUEST_TIMEOUT_MILLIS

/** Rate implied by [SCORE_RESPONSE_MAX_BYTES] and [OUTBOUND_REQUEST_TIMEOUT_MILLIS]. */
const val SCORE_RESPONSE_RATE_BYTES_PER_SECOND: Long =
    (SCORE_RESPONSE_MAX_BYTES * 1_000 + OUTBOUND_REQUEST_TIMEOUT_MILLIS - 1) /
        OUTBOUND_REQUEST_TIMEOUT_MILLIS

/** One independent three-permit gate per network-capable framework callback. */
const val NETWORK_HANDLER_CONCURRENCY: Int = 3

/**
 * Models one completing PostgreSQL operation without a whole-call deadline.
 *
 * A zero required rate records that the operation has no complete-call timeout;
 * concurrency one still makes it compete with timed network operations.
 */
@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
fun modelPostgresOperation() = Unit

/** Models one completing MongoDB operation without a whole-call deadline. */
@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
fun modelMongoOperation() = Unit

/** Models one completing RabbitMQ transfer without a whole-call deadline. */
@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
fun modelRabbitMqOperation() = Unit

/** Models one completing Solr operation without a whole-call deadline. */
@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
fun modelSolrOperation() = Unit

/** Models one completing R2 operation without a whole-call deadline. */
@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
fun modelR2Operation() = Unit
