# Bandwidth-timeout checker experiment

This branch applies the bandwidth-timeout checker to the BeatSaver JVM server.
It is an experiment, not a production capacity recommendation.

## Model

- `Application.beatmapsio` is the framework entry point.
- Every Ktor HTTP handler is a long-lived callback: requests may invoke it an
  unbounded number of times and overlap.
- Each handler that can perform an outbound download has its own semaphore with
  three permits. The gates are deliberately not shared between handlers, so the
  inferred application bound is the parallel composition of their individual
  bounds.
- Small JSON/API responses are assumed to be at most 1 MB and use a 20-second
  complete-request timeout.
- Image responses are assumed to be at most 8 MB and use a 60-second
  complete-request timeout.
- The experiment workload must not return responses larger than those bounds.
  The constants live in `BandwidthExperiment.kt` so another experimental
  configuration can change them in one place.

The annotations cover outbound Ktor HTTP calls reachable from the entry point,
including CAPTCHA verification, OAuth identity calls, score services,
webhooks, Cloudflare KV requests, game-token validation, and remote avatars.

An additional library-boundary pass now models ordinary completing operations:

| Boundary | Modeled sites | Interpretation |
|---|---:|---|
| Exposed/PostgreSQL | 163 | One active database operation per `transaction` or `newSuspendedTransaction` |
| MongoDB | 9 | One active operation per direct collection call |
| RabbitMQ publishing | 42 | One synchronous `basicPublish` call; broker acknowledgement is not awaited |
| Solr | 14 | One active operation, including a sequential retry sequence |
| R2 | 2 | One synchronous upload or deletion |

None of these clients currently configures a whole-call timeout. Their model
therefore uses `@BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)`: the zero
rate adds no timeout obligation, while concurrency one records that the
operation competes with timed transfers. PostgreSQL transactions contain no
`launch`, `async`, or `coroutineScope` in the inspected source, so one
transaction cannot issue parallel database operations in this model.

The library pass deliberately does not yet model Ktor server responses or the
RabbitMQ `consumeAck` registration itself. Those APIs need lifetime contracts
before their effects can be stated soundly.

The RabbitMQ publish model assumes that the transfer effect ends when
`basicPublish` returns. The library makes that call synchronously, but it does
not enable publisher confirms or wait for a broker acknowledgement. A stronger
delivery-level guarantee would therefore require a different contract.

## HTTP-only baseline result

With three permits per network-bearing handler, the checker reports:

```text
Inferred application entry-point effect from 1 entry point(s): {(400000/3, 79)}
ReqBW=31600000/3 bytes/s
```

That is approximately 10.53 MB/s (84.3 Mb/s). It is a conservative peak-demand
result: the effect representation raises all 79 possible concurrent downloads
to the largest configured per-download rate (8 MB / 60 seconds).

After adding the library-boundary effects, compilation intentionally stops on
140 remaining checker diagnostics rather than silently assuming a bound:

- 109 HTTP handlers need a three-permit semaphore around the complete handler
  body.
- 16 effectful RabbitMQ consumer callbacks need a callback concurrency model;
  either a semaphore must be held until the callback finishes or the checker
  must trust and understand `prefetchCount`.
- 15 higher-order calls need effect forwarding or a calls-in-place model. These
  include `requireCaptcha`, `captchaIfPresent`, Ktor `install` and authentication
  configuration, `genericPage`, `use`, and two function references passed to
  `map`.

Consequently, 10.53 MB/s remains the earlier HTTP-only baseline, not a current
whole-server guarantee. A new application bound should be recorded only after
the remaining callbacks receive sound runtime or checker-level bounds.

## Reproduce

Place sibling checkouts of `bandwidth-timeout-checker` and
`beatsaver-common-mp` next to this repository, then run with JDK 21:

```shell
./gradlew compileKotlinJvm --continue -PkabtOs=linux --console=plain
```

`kabtOs=linux` selects the project's existing Linux native dependency when the
analysis is compiled on macOS; the compiler only needs its API surface.

## Semaphore performance experiment

The checker experiment above establishes a static bound when the handler
semaphores are present. A separate controlled experiment measures the runtime
cost of admitting fewer downloads at once.

The benchmark models one network-bearing handler. Sixteen 1 MiB downloads
arrive together and share a work-conserving 8 MiB/s link. Each admitted request
also pays 40 ms before transferring, representing latency or remote processing
that cannot use the link. Active transfers receive an equal share of the fixed
bandwidth. This is the fair-sharing environment assumed by the calculus, not a
benchmark of the production BeatSaver deployment.

Run the five-trial experiment with:

```shell
python3 experiments/semaphore_bandwidth_experiment.py
```

A representative run on September 21, 2026 produced:

| Handler cap | Makespan (s) | Slowdown | Throughput (MiB/s) | Queue p50 (s) | Active p50 (s) | End-to-end p50 (s) | End-to-end p95 (s) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 2.659 | +30.1% | 6.016 | 1.246 | 0.167 | 1.412 | 2.534 |
| 2 | 2.344 | +14.6% | 6.827 | 1.022 | 0.293 | 1.314 | 2.343 |
| 3 | 2.250 | +10.0% | 7.112 | 0.833 | 0.417 | 1.250 | 2.124 |
| 4 | 2.171 | +6.2% | 7.370 | 0.815 | 0.542 | 1.357 | 2.171 |
| 8 | 2.084 | +2.0% | 7.676 | 0.521 | 1.042 | 1.563 | 2.084 |
| unbounded | 2.044 | +0.0% | 7.827 | 0.000 | 2.044 | 2.044 | 2.044 |

The three-permit gate increased total completion time by about 10.0% and
reduced throughput by about 9.1%. In return, the median active time fell by
about 80% because each admitted transfer received a larger share of the link.
The median end-to-end latency also improved, while later batches waited in the
queue and raised tail latency slightly. Smaller caps underused the link because
every batch paid the 40 ms non-transfer delay.

This result supports the hypothesis that a moderate concurrency bound can have
little effect on total transfer time when it is still large enough to keep the
link busy. It does not establish the cost in production: remote-service limits,
connection reuse, HTTP protocol behavior, payload variation, and multiple
independent handlers may change the result. A later deployment experiment
should repeat the comparison against a shaped network while exercising actual
BeatSaver routes.
