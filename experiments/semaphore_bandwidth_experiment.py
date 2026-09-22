#!/usr/bin/env python3
"""Measure the effect of a handler semaphore under a fixed fair-share link.

This controlled experiment isolates the mechanism assumed by the bandwidth
checker: all active downloads share one fixed-capacity link fairly. Requests
arrive together, wait for a handler semaphore, pay a fixed pre-transfer delay,
and then share the link with the other admitted requests.

The experiment does not benchmark the production BeatSaver deployment or its
remote services. It answers a narrower question: under the paper's network
model, how much makespan and latency does a bounded handler gate add?
"""

from __future__ import annotations

import argparse
import asyncio
import math
import statistics
from dataclasses import dataclass


MIB = 1024 * 1024


@dataclass
class Transfer:
    remaining_bytes: float
    completed: asyncio.Future[None]


class FairBandwidthLink:
    """A work-conserving link that divides each tick equally among transfers."""

    def __init__(self, bytes_per_second: float, tick_seconds: float) -> None:
        if bytes_per_second <= 0:
            raise ValueError("bytes_per_second must be positive")
        if tick_seconds <= 0:
            raise ValueError("tick_seconds must be positive")

        self.bytes_per_second = bytes_per_second
        self.tick_seconds = tick_seconds
        self._active: list[Transfer] = []
        self._lock = asyncio.Lock()
        self._closed = False
        self._runner = asyncio.create_task(self._run())

    async def transfer(self, size_bytes: int) -> None:
        if size_bytes <= 0:
            raise ValueError("size_bytes must be positive")

        loop = asyncio.get_running_loop()
        completed: asyncio.Future[None] = loop.create_future()
        transfer = Transfer(float(size_bytes), completed)
        async with self._lock:
            if self._closed:
                raise RuntimeError("link is closed")
            self._active.append(transfer)
        await completed

    async def close(self) -> None:
        self._closed = True
        await self._runner

    async def _run(self) -> None:
        loop = asyncio.get_running_loop()
        last_tick = loop.time()

        while not self._closed or self._active:
            await asyncio.sleep(self.tick_seconds)
            now = loop.time()
            budget = self.bytes_per_second * (now - last_tick)
            last_tick = now

            async with self._lock:
                active = [transfer for transfer in self._active if not transfer.completed.done()]

                # Redistribute bytes left by transfers that finish during this tick.
                while budget > 0 and active:
                    share = budget / len(active)
                    consumed = 0.0
                    unfinished: list[Transfer] = []

                    for transfer in active:
                        allocated = min(share, transfer.remaining_bytes)
                        transfer.remaining_bytes -= allocated
                        consumed += allocated

                        if transfer.remaining_bytes <= 1e-9:
                            transfer.completed.set_result(None)
                        else:
                            unfinished.append(transfer)

                    budget -= consumed
                    if len(unfinished) == len(active):
                        break
                    active = unfinished

                self._active = active


@dataclass(frozen=True)
class RequestTiming:
    queue_seconds: float
    active_seconds: float
    end_to_end_seconds: float


@dataclass(frozen=True)
class TrialResult:
    cap: int
    makespan_seconds: float
    throughput_mib_per_second: float
    timings: tuple[RequestTiming, ...]


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("cannot compute a percentile of an empty list")
    position = probability * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


async def run_trial(
    *,
    request_count: int,
    payload_bytes: int,
    link_bytes_per_second: float,
    pre_transfer_delay_seconds: float,
    tick_seconds: float,
    cap: int,
) -> TrialResult:
    loop = asyncio.get_running_loop()
    gate = asyncio.Semaphore(cap)
    link = FairBandwidthLink(link_bytes_per_second, tick_seconds)
    start_signal = asyncio.Event()
    trial_start = loop.time()

    async def request() -> RequestTiming:
        await start_signal.wait()
        arrived = loop.time()

        async with gate:
            admitted = loop.time()
            await asyncio.sleep(pre_transfer_delay_seconds)
            await link.transfer(payload_bytes)
            finished = loop.time()

        return RequestTiming(
            queue_seconds=admitted - arrived,
            active_seconds=finished - admitted,
            end_to_end_seconds=finished - arrived,
        )

    tasks = [asyncio.create_task(request()) for _ in range(request_count)]
    trial_start = loop.time()
    start_signal.set()
    timings = tuple(await asyncio.gather(*tasks))
    makespan = loop.time() - trial_start
    await link.close()

    total_mib = request_count * payload_bytes / MIB
    return TrialResult(
        cap=cap,
        makespan_seconds=makespan,
        throughput_mib_per_second=total_mib / makespan,
        timings=timings,
    )


def parse_caps(raw_caps: str, request_count: int) -> list[int]:
    caps = [int(part.strip()) for part in raw_caps.split(",") if part.strip()]
    if not caps or any(cap <= 0 for cap in caps):
        raise ValueError("caps must be a comma-separated list of positive integers")
    if request_count not in caps:
        caps.append(request_count)
    return sorted(set(min(cap, request_count) for cap in caps))


def mean_metric(results: list[TrialResult], extractor) -> float:
    return statistics.mean(extractor(result) for result in results)


async def main_async(args: argparse.Namespace) -> None:
    caps = parse_caps(args.caps, args.requests)
    grouped: dict[int, list[TrialResult]] = {cap: [] for cap in caps}

    for cap in caps:
        for _ in range(args.trials):
            grouped[cap].append(
                await run_trial(
                    request_count=args.requests,
                    payload_bytes=args.payload_kib * 1024,
                    link_bytes_per_second=args.link_mib_per_second * MIB,
                    pre_transfer_delay_seconds=args.pre_transfer_delay_ms / 1000,
                    tick_seconds=args.tick_ms / 1000,
                    cap=cap,
                )
            )

    baseline_cap = args.requests
    baseline_makespan = mean_metric(grouped[baseline_cap], lambda result: result.makespan_seconds)

    print("Controlled fair-link semaphore experiment")
    print(
        f"requests={args.requests}, payload={args.payload_kib} KiB, "
        f"link={args.link_mib_per_second:g} MiB/s, "
        f"pre-transfer delay={args.pre_transfer_delay_ms:g} ms, trials={args.trials}"
    )
    print()
    print("| Handler cap | Makespan (s) | Slowdown | Throughput (MiB/s) | Queue p50 (s) | Active p50 (s) | End-to-end p50 (s) | End-to-end p95 (s) |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|")

    for cap in caps:
        results = grouped[cap]
        makespan = mean_metric(results, lambda result: result.makespan_seconds)
        throughput = mean_metric(results, lambda result: result.throughput_mib_per_second)
        queue_p50 = mean_metric(
            results,
            lambda result: percentile([timing.queue_seconds for timing in result.timings], 0.50),
        )
        active_p50 = mean_metric(
            results,
            lambda result: percentile([timing.active_seconds for timing in result.timings], 0.50),
        )
        end_to_end_p50 = mean_metric(
            results,
            lambda result: percentile([timing.end_to_end_seconds for timing in result.timings], 0.50),
        )
        end_to_end_p95 = mean_metric(
            results,
            lambda result: percentile([timing.end_to_end_seconds for timing in result.timings], 0.95),
        )
        label = "unbounded" if cap == args.requests else str(cap)
        slowdown = makespan / baseline_makespan - 1

        print(
            f"| {label} | {makespan:.3f} | {slowdown:+.1%} | {throughput:.3f} | "
            f"{queue_p50:.3f} | {active_p50:.3f} | {end_to_end_p50:.3f} | "
            f"{end_to_end_p95:.3f} |"
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=16)
    parser.add_argument("--payload-kib", type=int, default=1024)
    parser.add_argument("--link-mib-per-second", type=float, default=8.0)
    parser.add_argument("--pre-transfer-delay-ms", type=float, default=40.0)
    parser.add_argument("--tick-ms", type=float, default=5.0)
    parser.add_argument("--trials", type=int, default=5)
    parser.add_argument("--caps", default="1,2,3,4,8")
    return parser


def validate_args(args: argparse.Namespace) -> None:
    positive = {
        "requests": args.requests,
        "payload_kib": args.payload_kib,
        "link_mib_per_second": args.link_mib_per_second,
        "tick_ms": args.tick_ms,
        "trials": args.trials,
    }
    for name, value in positive.items():
        if value <= 0:
            raise ValueError(f"{name} must be positive")
    if args.pre_transfer_delay_ms < 0:
        raise ValueError("pre_transfer_delay_ms must be non-negative")


def main() -> None:
    args = build_parser().parse_args()
    validate_args(args)
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
