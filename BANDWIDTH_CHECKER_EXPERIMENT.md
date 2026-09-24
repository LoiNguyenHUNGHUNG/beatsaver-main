# Bandwidth-timeout checker experiment

This branch applies the bandwidth-timeout checker to the BeatSaver JVM server.
It is an experiment, not a production capacity recommendation.

## Model

- `Application.beatmapsio` is the framework entry point.
- Every Ktor HTTP handler is a long-lived callback: requests may invoke it an
  unbounded number of times and overlap.
- Each handler that can perform a modeled network operation has its own semaphore with
  three permits. The gates are deliberately not shared between handlers, so the
  inferred application bound is the parallel composition of their individual
  bounds.
- Profiled score responses are assumed to be at most 7 KiB and use a 20-second
  complete-request timeout.
- Other small JSON/API responses are assumed to be at most 16 KiB and use a
  20-second complete-request timeout. This is a separate assumption for the
  authenticated services that could not be profiled without credentials.
- The playlist-avatar path is exercised with BeatSaver account `58338`
  (`Joetastic`) and its fixed 26,482-byte avatar response.
- The Discord-avatar path is exercised directly with public fixture account
  `500064999524401152` (`feroxf`) and its fixed 40,234-byte avatar response.
  Both avatar paths use a 60-second complete-request timeout.
- The experiment workload must not return responses larger than those bounds.
  The constants live in `BandwidthExperiment.kt` so another experimental
  configuration can change them in one place.
- Inbound request bodies, including multipart uploads, are outside the property
  and do not contribute a download effect.

### Small-workload profile

The response ceilings above come from a September 23, 2026 profiling run that
kept pagination and media inputs small. It made requests directly to the same
public upstream endpoints used by BeatSaver, without changing server logic.

| Response class | Samples | Largest observed body | Experimental ceiling |
|---|---:|---:|---:|
| ScoreSaber leaderboard responses | 10 selected hashes (8 successful score pages) | 6,894 B | 7 KiB |
| BeatLeader first page (`count=12`) | 10 popular-map hashes | 2,591 B | 7 KiB |
| BeatSaver playlist avatar | Joetastic (`id=58338`) | 26,482 B | 26,482 B |
| Discord avatar | feroxf (`id=500064999524401152`) | 40,234 B | 40,234 B |

The avatar ceilings describe only these exact fixtures, not universal API
limits. Profiling and runtime execution must use the same URLs and reject a
response whose body no longer matches the recorded size:

```text
https://cdn.beatsaver.com/avatar/91bb7b0510cea647179bc95e032f8a25608b09f1.png
https://cdn.discordapp.com/avatars/500064999524401152/26353295679abfb6d36c20ba18695bdb.png
```

CAPTCHA, OAuth, Steam, Cloudflare, and webhook
responses require credentials for successful end-to-end profiling; their 16 KiB
ceiling is therefore an explicit workload assumption based on the small response
shapes consumed by BeatSaver rather than an empirical maximum. These unprofiled
responses retain their separate 16 KiB ceiling instead of inheriting the score
ceiling.

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

Ktor server responses remain outside the model. RabbitMQ `consumeAck`
callbacks are modeled as retained handlers, with one independent three-permit
semaphore for each effectful consumer.

The RabbitMQ publish model assumes that the transfer effect ends when
`basicPublish` returns. The library makes that call synchronously, but it does
not enable publisher confirms or wait for a broker acknowledgement. A stronger
delivery-level guarantee would therefore require a different contract.

## Results

The original HTTP-only pass reported:

```text
Inferred application entry-point effect from 1 entry point(s): {(400000/3, 79)}
ReqBW=31600000/3 bytes/s
```

That is approximately 10.53 MB/s (84.3 Mb/s). This value is retained as a
baseline only; it excludes the later library-boundary models.

The library-boundary pass exposed 109 additional effectful HTTP handlers. Each
now has its own immutable top-level three-permit semaphore, held for the entire
handler body. This removes all HTTP-handler repetition diagnostics. The 16
effectful RabbitMQ `consumeAck` callbacks likewise have independent immutable
top-level three-permit semaphores held across their complete bodies.

Effect-polymorphic contracts now preserve callback effects through BeatSaver's
authorization, CAPTCHA, multipart, page-template, and Ktor configuration
helpers. Ktor plugin configuration blocks are invoked once. Authentication
callbacks retained by Ktor or the OAuth library are marked as handlers and
execute under three-permit semaphores. The multipart reader was changed from
recursion to an equivalent loop so its callback effect remains visible without
requiring a concrete recursive summary.

Declaration-level handler contracts model the network-bearing service methods
that opaque frameworks invoke after registration. This covers three MongoDB
session-storage methods, three OAuth client-service methods, seven OAuth
token-store methods, and one identity-service method. Each method has its own
three-permit runtime semaphore. The checker treats every declaration-level
handler as an independent, repeatedly invocable application root while keeping
an ordinary direct call to that method local to its caller.

Before payload profiling, the deliberately broad 1 MB/8 MB ceilings produced:

```text
Inferred application entry-point effect from 15 entry point(s): {(400000/3, 469)}
ReqBW=187600000/3 bytes/s
```

That is approximately 62.53 MB/s (500.3 Mb/s). After replacing those guessed
ceilings with the 7 KiB score and fixed-avatar profiles, retaining a separate
16 KiB assumption for unprofiled API responses, and excluding inbound multipart
reads, a fresh full compilation reports:

```text
Inferred application entry-point effect from 15 entry point(s): {(4096/5, 469)}
ReqBW=1921024/5 bytes/s
```

That is approximately 0.384 MB/s (3.07 Mb/s). It remains a conservative
peak-demand result: the effect representation raises all 469 possibly
concurrent operations to the largest configured per-operation rate. That rate
currently comes from the separate 16 KiB / 20 second assumption for unprofiled
small API responses, not either avatar fixture. Operations with no whole-call
timeout contribute a zero rate but still increase the concurrency component
because they compete with timed transfers.

This is the result for the experiment's explicit source-level contracts, not a
production capacity recommendation. The fourteen new handler roots account for
42 additional possible operations because each handler has three permits. This
closes the previously identified gaps for Ktor Sessions and the OAuth
`ClientService`, `TokenStore`, and `IdentityService` interfaces; adding another
opaque framework would still require a contract for its retained callbacks.

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
