#!/usr/bin/env python3
"""
Fast Sieve of Eratosthenes for primes up to 1,000,000,000.

Optimizations:
  * Odds-only segmented sieve (skip even numbers entirely).
  * Per sieving prime, mark composites via a single numpy slice
    assignment `seg[start::p] = False` — runs at C speed inside numpy
    instead of building Python-side index arrays.
  * Precomputed per-prime "next start index" carried across segments,
    so we never recompute it from scratch.
  * Large segments (~1 MiB bool array) to amortize Python overhead while
    still fitting comfortably in L2/L3 cache.
  * Bulk ASCII formatting of each segment's primes via
    `' '.join(map(str, ...))` (C-implemented in CPython) and a single
    write per segment to a file opened with a 4 MiB buffer.
  * Per-phase elapsed times plus total wall time.

Output: primes.txt (space-delimited)
"""

from __future__ import annotations

import math
import sys
import time

import numpy as np

LIMIT = 1_000_000_000
SEGMENT_HALF = 1 << 20           # 1,048,576 odd numbers per segment (~2.1M ints, ~1 MiB bool)
WRITE_BUFFER_BYTES = 4 << 20     # 4 MiB stdio buffer


# ---------------------------------------------------------------------------
# Timing helper
# ---------------------------------------------------------------------------
class Timer:
    def __init__(self) -> None:
        self.records: list[tuple[str, float]] = []

    def section(self, name: str):
        outer = self

        class _Ctx:
            def __enter__(self_inner):
                self_inner.t0 = time.perf_counter()
                return self_inner

            def __exit__(self_inner, exc_type, exc, tb):
                dt = time.perf_counter() - self_inner.t0
                outer.records.append((name, dt))
                print(f"  [{name}] {dt:.3f}s", file=sys.stderr)

        return _Ctx()

    def report(self, total: float) -> None:
        print("\n=== Timing summary ===", file=sys.stderr)
        for name, dt in self.records:
            print(f"  {name:<32} {dt:8.3f}s", file=sys.stderr)
        print(f"  {'TOTAL':<32} {total:8.3f}s", file=sys.stderr)


# ---------------------------------------------------------------------------
# Base sieve: all primes up to sqrt(LIMIT)
# ---------------------------------------------------------------------------
def base_primes(limit: int) -> np.ndarray:
    """Standard odds-only sieve returning primes <= limit as int64 array."""
    if limit < 2:
        return np.array([], dtype=np.int64)
    n_odd = (limit - 1) // 2 + 1                 # indices 0..n_odd-1 -> 1,3,5,...
    sieve = np.ones(n_odd, dtype=bool)
    sieve[0] = False                             # 1 is not prime
    for i in range(1, int(math.isqrt(limit)) // 2 + 1):
        if sieve[i]:
            p = 2 * i + 1
            sieve[(p * p - 1) // 2 :: p] = False
    odd_primes = (np.nonzero(sieve)[0] * 2 + 1).astype(np.int64)
    return np.concatenate(([2], odd_primes))


# ---------------------------------------------------------------------------
# Segmented sieve + writer fused into one pass.
#
# Each segment of SEGMENT_HALF entries represents the odd integers
# [seg_low_odd, seg_low_odd + 2, ..., seg_low_odd + 2*(SEGMENT_HALF-1)].
# Index k -> integer  seg_low_odd + 2*k.
# ---------------------------------------------------------------------------
def sieve_and_write(path: str, limit: int, primes: np.ndarray, timer: Timer) -> int:
    odd_primes = primes[primes >= 3].astype(np.int64)

    # next_idx[i] = next index in the *current* segment to mark for odd_primes[i]
    # We initialize for the first segment (starting at integer 1, index 0 -> 1).
    # For each prime p, the smallest odd multiple of p that is >= p*p is p*p itself
    # (since p is odd, p*p is odd). Its segment index is (p*p - 1) // 2.
    next_idx = ((odd_primes * odd_primes - 1) // 2).astype(np.int64)

    seg = np.empty(SEGMENT_HALF, dtype=bool)
    n_total_odds = (limit - 1) // 2 + 1          # total odd integers in [1, limit]
    seg_count = (n_total_odds + SEGMENT_HALF - 1) // SEGMENT_HALF

    count = 0
    write = None  # set after opening file

    with timer.section("segmented sieve + write"):
        with open(path, "w", buffering=WRITE_BUFFER_BYTES, encoding="ascii", newline="") as f:
            write = f.write
            # Emit the only even prime first.
            write("2\n")
            count += 1

            for s in range(seg_count):
                seg_lo = s * SEGMENT_HALF                       # index of first odd in seg (odd value = 2*seg_lo + 1)
                seg_hi = min(seg_lo + SEGMENT_HALF, n_total_odds)
                width = seg_hi - seg_lo
                seg[:width] = True

                # In segment 0, index 0 corresponds to integer 1, which is not prime.
                if s == 0:
                    seg[0] = False

                # For every sieving prime, mark composites in this segment.
                # Use Python-level loop, but each iteration is a single C-speed slice.
                ni = next_idx
                op = odd_primes
                # Only primes whose square is within the global range matter.
                # Once p*p > 2*global_high+1 we can stop, but we cap by walking
                # the relative offset against `width`.
                seg_view = seg[:width]
                for i in range(op.size):
                    off = ni[i] - seg_lo
                    if off >= width:
                        # Nothing to mark in this segment for this prime.
                        continue
                    p = op[i]
                    seg_view[off::p] = False
                    # Advance next_idx to the first composite in the *next* segment.
                    # Number of marks made in this segment:
                    marks = (width - off + p - 1) // p
                    ni[i] = ni[i] + marks * p

                # Collect primes from this segment and write them.
                true_idx = np.flatnonzero(seg_view)
                if true_idx.size:
                    # integer value = 2*(seg_lo + idx) + 1
                    vals = (true_idx + seg_lo) * 2 + 1
                    # Filter the tail of the last segment so we don't emit > limit.
                    if s == seg_count - 1:
                        vals = vals[vals <= limit]
                    if vals.size:
                        # Bulk ASCII format: '\n'.join(map(str, ...)) is C-fast in CPython.
                        write("\n".join(map(str, vals.tolist())))
                        write("\n")
                        count += vals.size

    return count


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> None:
    timer = Timer()
    t_start = time.perf_counter()

    sqrt_limit = int(math.isqrt(LIMIT))
    print(f"Sieving primes up to {LIMIT:,} (sqrt = {sqrt_limit:,})", file=sys.stderr)
    print(f"Segment size: {SEGMENT_HALF:,} odd entries (~{SEGMENT_HALF // 1024} KiB)", file=sys.stderr)

    with timer.section("base primes (<= sqrt N)"):
        primes = base_primes(sqrt_limit)
        print(f"  base primes found: {primes.size:,}", file=sys.stderr)

    count = sieve_and_write("primes.txt", LIMIT, primes, timer)

    total = time.perf_counter() - t_start
    print(f"\nWrote {count:,} primes to primes.txt", file=sys.stderr)
    timer.report(total)


if __name__ == "__main__":
    main()
