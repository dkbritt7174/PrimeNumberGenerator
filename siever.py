#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Prime generator up to 1 000 000 000 (inclusive).

The script uses a segmented sieve accelerated by NumPy and runs the segment
processing in parallel on all available CPU cores.
After printing timing information it pauses for a key press so that you can
inspect the output before the console window closes.
"""

import time
from math import isqrt
from collections import defaultdict
from functools import wraps
from multiprocessing import Pool, cpu_count
import numpy as np


# --------------------------------------------------------------------------- #
# Helper: human‑readable elapsed time
# --------------------------------------------------------------------------- #
def human_time(seconds: float) -> str:
    """
    Convert a floating point number of seconds into a string with an
    appropriate unit (ns, µs, ms or s).

    Parameters
    ----------
    seconds : float
        Elapsed time in seconds.

    Returns
    -------
    str
        Human‑readable representation, e.g. ``"3.12 s"``, ``"45 ms"``,
        ``"1.5 µs"`` or ``"200 ns"``.
    """
    if seconds < 1e-6:
        return f"{seconds * 1e9:.0f} ns"
    if seconds < 1e-3:
        return f"{seconds * 1e6:.0f} µs"
    if seconds < 1.0:
        return f"{seconds * 1e3:.0f} ms"
    return f"{seconds:.2f} s"


# --------------------------------------------------------------------------- #
# Timing decorator
# --------------------------------------------------------------------------- #
_cumulative_times = defaultdict(float)


def timed(func):
    """
    Decorator that measures how long a function takes to execute and keeps a
    running total for all calls of that function.

    The wrapped function gains an ``elapsed()`` method that returns the
    accumulated time in seconds.
    """

    @wraps(func)
    def wrapper(*args, **kwargs):
        start = time.perf_counter()
        result = func(*args, **kwargs)
        elapsed = time.perf_counter() - start
        _cumulative_times[func.__name__] += elapsed
        return result

    wrapper.elapsed = lambda: _cumulative_times[func.__name__]
    return wrapper


# --------------------------------------------------------------------------- #
# 1️⃣ Base sieve – primes up to sqrt(N)
# --------------------------------------------------------------------------- #
@timed
def base_sieve(limit: int) -> np.ndarray:
    """
    Compute all prime numbers ≤ ``limit`` using the classic odd‑only sieve.

    Parameters
    ----------
    limit : int
        Upper bound (inclusive).  Must be ≥ 2; otherwise an empty array is
        returned.

    Returns
    -------
    numpy.ndarray
        Sorted one‑dimensional array of type ``int64`` containing all primes.
    """
    if limit < 2:
        return np.array([], dtype=np.int64)

    size = limit // 2 + 1  # only odd numbers are stored
    sieve = np.ones(size, dtype=bool)

    sqrt_lim = isqrt(limit)
    for p in range(3, sqrt_lim + 1, 2):
        if sieve[p // 2]:
            start_idx = (p * p) // 2
            step = p
            sieve[start_idx::step] = False

    primes = [2]
    odd_indices = np.nonzero(sieve)[0][1:]  # skip index 0 → number 1
    primes.extend((odd_indices << 1) + 1)
    return np.array(primes, dtype=np.int64)


# --------------------------------------------------------------------------- #
# 2️⃣ Worker for a single segment (CPU version)
# --------------------------------------------------------------------------- #
@timed
def cpu_worker(args):
    """
    Process one segmented block of the sieve.

    Parameters
    ----------
    args : tuple
        ``(low, high, base_primes)``
        * ``low``  – first odd number of the segment.
        * ``high`` – exclusive upper bound of the segment (inclusive for
          prime testing).
        * ``base_primes`` – array of all primes ≤ √max_n.

    Returns
    -------
    list[int]
        All primes found in the given segment.  The list is already sorted
        because the segment is processed in ascending order.
    """
    low, high, base_primes = args

    seg_size = high - low
    seg_sieve = np.ones(seg_size // 2 + 1, dtype=bool)

    for p in base_primes:
        if p * p > high:
            break

        start = max(p * p, ((low + p - 1) // p) * p)
        if start % 2 == 0:
            start += p

        seg_sieve[(start - low) // 2 :: p] = False

    odds = np.nonzero(seg_sieve)[0]
    return (low + odds * 2).tolist()


# --------------------------------------------------------------------------- #
# 3️⃣ Main driver
# --------------------------------------------------------------------------- #
def main():
    """
    Orchestrate the prime generation:

    1. Compute base primes up to √max_n.
    2. Split the range [3, max_n] into segments.
    3. Run each segment in parallel using ``multiprocessing.Pool``.
    4. Merge and sort the results.
    5. Write the primes to a space‑delimited file with 100 numbers per line.
    6. Print timing information for each step.
    7. Pause until the user presses <Enter>.
    """
    max_n = 1_000_000_000  # inclusive upper bound
    seg_sz = 10_000_000  # segment size – tweak for memory

    print(f"=== Prime generator up to {max_n:,} ===")
    t_start = time.perf_counter()

    # ---- 1️⃣ base primes ------------------------------------------- #
    base_primes = base_sieve(isqrt(max_n) + 1)
    print(f"[1] Base primes (≤ √N): {len(base_primes)} found.")

    # ---- 2️⃣ create segment ranges ---------------------------------- #
    ranges = [
        (l, min(l + seg_sz, max_n + 1)) for l in range(3, max_n + 1, seg_sz)
    ]
    print(f"[2] Split into {len(ranges)} segments of size ~{seg_sz:,}.")

    # ---- 3️⃣ parallel segmented sieve ------------------------------- #
    with Pool(cpu_count()) as pool:
        all_lists = pool.map(
            cpu_worker, [(l, h, base_primes) for l, h in ranges]
        )

    print("[3] Parallel segmented sieve finished.")

    # ---- 4️⃣ combine and sort --------------------------------------- #
    primes_all = [p for sublist in all_lists for p in sublist]
    primes_all.sort()
    total_primes = len(primes_all)
    print(f"[4] Total primes found: {total_primes:,}")

    # ---- 5️⃣ write to file ------------------------------------------ #
    out_file = "primes.txt"
    with open(out_file, "w", encoding="utf-8") as f:
        for i in range(0, total_primes, 25):
            line = " ".join(map(str, primes_all[i : i + 25]))
            f.write(line + "\n")

    print(
        f"[5] Primes written to ``{out_file}`` (space‑delimited, 25 columns)."
    )

    t_elapsed = time.perf_counter() - t_start
    print("\n--- Timing summary ---")
    print(f"Total elapsed time: {human_time(t_elapsed)}")
    print(f"  base_sieve     : {human_time(base_sieve.elapsed())}")
    print(f"  cpu_worker     : {human_time(cpu_worker.elapsed())}")

    # ------------------------------------------------------------------- #
    # Pause until user presses Enter
    input("\nPress <Enter> to exit…")


# --------------------------------------------------------------------------- #
if __name__ == "__main__":
    main()
