# Bandwidth-timeout checker experiment

This branch applies the bandwidth-timeout checker to the BeatSaver JVM server.
It is an experiment, not a production capacity recommendation.

## Model

- `Application.beatmapsio` is the framework entry point.
- Every Ktor HTTP handler is a long-lived callback: requests may invoke it an
  unbounded number of times and overlap.
- Each handler that can perform an outbound download has its own semaphore with
  eight permits. The gates are deliberately not shared between handlers, so the
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
Inbound request bodies and non-HTTP services such as PostgreSQL, RabbitMQ,
Solr, and the R2 SDK are outside this experiment's bandwidth model.

## Result

With eight permits per network-bearing handler, the checker reports:

```text
Inferred application entry-point effect from 1 entry point(s): {(400000/3, 209)}
ReqBW=83600000/3 bytes/s
```

That is approximately 27.87 MB/s (223 Mb/s). It is a conservative peak-demand
result: the effect representation raises all 209 possible concurrent downloads
to the largest configured per-download rate (8 MB / 60 seconds).

## Reproduce

Place sibling checkouts of `bandwidth-timeout-checker` and
`beatsaver-common-mp` next to this repository, then run with JDK 21:

```shell
./gradlew compileKotlinJvm --continue -PkabtOs=linux --console=plain
```

`kabtOs=linux` selects the project's existing Linux native dependency when the
analysis is compiled on macOS; the compiler only needs its API surface.
