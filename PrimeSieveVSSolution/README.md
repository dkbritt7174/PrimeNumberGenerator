# PrimeSieve — Primes up to 1 Billion

High-performance C++ console application that finds all primes up to **1,000,000,000**
using a heavily optimized Sieve of Eratosthenes.

## Features

| Technique | Description |
|---|---|
| **Bit-packed sieve** | 1 bit per odd candidate — 8× memory reduction vs. `bool[]` |
| **Segmented sieve** | 64 KB cache-friendly segments (fits in L1 + L2 cache) |
| **2-wheel** | Even numbers skipped entirely — sieve is odds-only |
| **LTCG + AVX2** | Release build uses Link-Time Code Generation and AVX2 SIMD intrinsics |
| **Buffered file I/O** | 64 MB `fwrite` buffer — minimizes system call overhead |
| **Per-phase timing** | `QueryPerformanceCounter` gives sub-millisecond phase times |

## Building

Open `PrimeSieve.sln` in **Visual Studio 2022** and build the **Release | x64** configuration.

Requirements:
- Visual Studio 2022 (v143 toolset)
- Windows 10 SDK (10.0)
- C++20 or later

## Running

After building, run from the Release output directory:

```
x64\Release\PrimeSieve.exe
```

The executable writes **primes.txt** in the current working directory.

## Output

`primes.txt` — space-delimited list of all 50,847,534 primes up to 1,000,000,000.

```
2 3 5 7 11 13 17 19 23 29 31 37 41 43 47 ...
```

## Console Output (example timings)

```
=== Prime Sieve: Finding all primes up to 1000000000 ===

[Phase 1] Building small prime sieve (up to sqrt(1000000000))...
          Found 3400 small primes. Elapsed: 0.1 ms

[Phase 2] Running segmented sieve (segment = 524288 bits = 64 KB)...
          Found 50847534 primes up to 1000000000. Elapsed: ~800 ms

[Phase 3] Writing primes.txt (50847534 primes)...
          Done. Elapsed: ~600 ms

=== Summary ===
  Phase 1 (small sieve):         0.1 ms
  Phase 2 (segmented sieve):   800.0 ms
  Phase 3 (file write):        600.0 ms
  Total elapsed:              ~1400.0 ms

Output written to: primes.txt
```

Actual timings vary by CPU. On a modern x64 processor with AVX2, expect:
- Sieve phase: ~500–900 ms
- File write: ~400–700 ms
- Total: ~1–2 seconds

## Architecture Notes

### Bit-Packing
Only odd numbers are stored. The candidate `2*i + 3` maps to bit index `i`.
This halves memory vs. a byte array and doubles the effective cache capacity.

### Segmented Sieve
The sieve is divided into 64 KB windows. For each window, composites are
marked using only the small primes from Phase 1 (`≤ √1B ≈ 31623`).
Each window fits in L1+L2 cache, dramatically reducing cache misses.

### Buffered Output
A 64 MB `char` buffer accumulates formatted output before calling `fwrite`.
A hand-rolled integer-to-ASCII converter avoids `sprintf`/`printf` overhead.

### Release Optimizations (vcxproj)
- `/Ox /Ot /Oi /Oy` — full speed, intrinsics, omit frame pointers
- `/arch:AVX2` — 256-bit SIMD auto-vectorization
- `/GL` + `/LTCG` — cross-module inlining and optimization
- `/fp:fast` — relaxed FP for faster sqrt
- Static CRT (`/MT`) — no runtime DLL dependency
