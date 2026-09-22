package io.beatmaps.util

import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

/** Complete-call timeout used by the bounded-response experiment. */
const val OUTBOUND_REQUEST_TIMEOUT_MILLIS: Long = 20_000

/** Maximum JSON or acknowledgement response admitted by the experiment. */
const val SMALL_RESPONSE_MAX_BYTES: Long = 1_000_000

/** Maximum image response admitted by the experiment. */
const val IMAGE_RESPONSE_MAX_BYTES: Long = 8_000_000

/** Existing complete-call timeout for avatar downloads. */
const val IMAGE_REQUEST_TIMEOUT_MILLIS: Long = 60_000

/** Rate implied by [SMALL_RESPONSE_MAX_BYTES] and [OUTBOUND_REQUEST_TIMEOUT_MILLIS]. */
const val SMALL_RESPONSE_RATE_BYTES_PER_SECOND: Long =
    (SMALL_RESPONSE_MAX_BYTES * 1_000 + OUTBOUND_REQUEST_TIMEOUT_MILLIS - 1) /
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
