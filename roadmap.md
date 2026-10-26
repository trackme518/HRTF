# HRTF Roadmap

Binaural-only improvement plan for the HRTF library.

This roadmap is intentionally limited to the HRTF binaural path: SOFA input,
HRIR interpolation, HRTF convolution, and the mono/stereo output path. It does
not add ambisonics, room simulation, distance/occlusion, near-field effects,
speaker decoding, native code, or a bundled HRIR database.

## Scope and principles

- Keep the library focused: **SOFA in, binaural stereo out**.
- Keep it pure Java, with no native build and no new runtime dependencies.
- Preserve the current design: direct two-filter (left/right) convolution with
  spherical-harmonic interpolation between measured directions.
- Prefer correctness, memory, startup time, and smoothness over new features.
- Every change must preserve or improve the existing public API unless a
  deliberate API addition is listed.

## Status key

| Symbol | Meaning |
|---|---|
| `[ ]` | Planned |
| `[~]` | In progress |
| `[x]` | Done |
| `[!]` | Blocked / needs investigation |

Priority levels:

| Level | Theme | Goal |
|---|---|---|
| **L0** | Critical correctness | Correct output for any valid SOFA file; no wasted work |
| **L1** | High impact | Lower memory, faster startup, smooth motion |
| **L2** | Quality | Better resampling, optional FFT and interpolation upgrades |
| **L3** | Polish | Documentation, tests, small API/robustness improvements |
| **Out** | Out of scope | Explicitly not part of this library |

## Summary

| ID | Level | Item | Effort | Status |
|---|---|---|---|---|
| L0-1 | L0 | Correct SOFA metadata handling | S | `[ ]` |
| L0-2 | L0 | Fix azimuth convention and add ILD test | S | `[ ]` |
| L0-3 | L0 | Render one ear per `HRTFUgen` | S | `[ ]` |
| L0-4 | L0 | Make SH order safe and configurable | S | `[ ]` |
| L1-1 | L1 | Stream the SH fit instead of storing all spectra | M | `[ ]` |
| L1-2 | L1 | Fit only the non-negative frequency bins | S | `[ ]` |
| L1-3 | L1 | Parallelize the SH fit and HRIR preparation | S | `[ ]` |
| L1-4 | L1 | Crossfade or smooth direction changes | S | `[ ]` |
| L2-1 | L2 | Real-input FFT / half-spectrum processing | M | `[ ]` |
| L2-2 | L2 | Better HRIR resampling | S | `[ ]` |
| L2-3 | L2 | Frequency-split interpolation (research) | L | `[ ]` |
| L2-4 | L2 | Optional normalization / diffuse-field EQ | M | `[ ]` |
| L2-5 | L2 | Optional fitted-coefficient cache | M | `[ ]` |
| L3-1 | L3 | Documentation and examples cleanup | S | `[ ]` |
| L3-2 | L3 | Extend the development test harness | S | `[ ]` |
| L3-3 | L3 | Small API/robustness polish | S | `[ ]` |

Effort: **S** = small, **M** = medium, **L** = large/research.

## Level 0 - Critical correctness

### L0-1 Correct SOFA metadata handling

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/SofaHrirSet.java`

**Problem**

The loader currently reads `Global/SampleRate` and only falls back to a
file-level `SampleRate` attribute inside a `catch` block. The bundled
`KEMAR_s.sofa` has neither; it stores the rate in `Data.SamplingRate`
(44100 Hz). The fallback is not reached when a dataset is simply missing, so
the loader silently keeps the hardcoded 44100 Hz. A 48 kHz SOFA will therefore
be resampled incorrectly.

The loader also ignores `SourcePosition/Type` (cartesian vs spherical),
`ReceiverPosition`, and `Data.Delay`.

**Change**

- Read `Data.SamplingRate` as the primary source; keep a real fallback for
  legacy files.
- Read `SourcePosition/Type` and either convert cartesian positions to
  spherical or fail with a clear error.
- Read `ReceiverPosition` to validate or determine left/right channel order.
- Read `Data.Delay`; use only a per-ear differential when present. Do not add a
  constant bulk delay (the bundled file has a nearly constant ~43476-43496
  sample delay).
- Validate dataset shapes and units and report actionable errors.
- Remove the silent 44100 Hz default for files that explicitly declare another
  rate.

**Acceptance**

- A 48 kHz SOFA renders at the correct speed and spectral positions.
- The bundled KEMAR still renders correctly.
- Cartesian `SourcePosition` is handled or rejected with a clear message.
- A missing/unsupported sample rate is not silently treated as 44.1 kHz.
- Unit/manual test covers sample-rate selection and channel order.

### L0-2 Fix azimuth convention and add ILD test

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTF.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`docs/reference.md`, `dev/TestHarness.java`

**Problem**

In the bundled SOFA, azimuth +90 degrees places the source on the **right**
(right-ear IR has much more energy and arrives earlier). The class docs and
reference say "90 = left", and `HRTFProcessor` even says "90 = left? see note".
`position(PVector)` with X forward and Z right is consistent with the measured
data, so the float-angle documentation is inverted relative to the default
dataset.

**Change**

- Choose one documented convention and apply it consistently.
- For the bundled KEMAR, document positive azimuth as right.
- Update `HRTF(position)` and `HRTFProcessor.setPosition()` docs and
  `docs/reference.md`.
- Add an objective test that checks which ear is louder and which onset comes
  first at +90 and -90/270 degrees.

**Acceptance**

- Docs, examples, and actual output agree.
- `TestHarness` asserts the expected ILD/ITD sign at +/-90 degrees.
- No accidental left/right swap in `position(PVector)`.

### L0-3 Render one ear per `HRTFUgen`

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HRTFUgen.java`

**Problem**

`HRTF` creates two full-stereo `HRTFProcessor` instances, one for the left
Effect unit and one for the right. Each processor computes both ears, but each
unit only uses its own side. Roughly half of the convolution and spectrum
reconstruction work is discarded.

**Change**

- Add a package-private left-only / right-only processing path, or an ear flag
  to the renderer.
- Keep the existing public `process(monoIn, outL, outR)` for standalone use.
- Each `HRTFUgen` should request only its own ear.

**Acceptance**

- Output is bit-comparable (or within float tolerance) with the current
  implementation for mono and stereo inputs.
- Convolution/FFT work in Effect mode is approximately halved.
- Standalone `BinauralOutput` behavior is unchanged.

### L0-4 Make SH order safe and configurable

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTF.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HrirInterpolatorSH.java`

**Problem**

The default order 5 needs at least 36 measurement directions. Sparse SOFAs can
fail at load. The order is also not exposed by the `HRTF` class, only by
`HRTFProcessor`.

**Change**

- Auto-cap the requested order to the number of available directions.
- Optionally regularize the normal-equation solve for ill-conditioned grids.
- Add `HRTF(PApplet parent, String sofaPath, int shOrder)`.
- Report the effective order and a clear warning when it was reduced.

**Acceptance**

- A SOFA with few directions loads successfully at the largest safe order.
- Existing default behavior is unchanged for dense datasets.
- Users can choose order 3/5/7 where the data supports it.

## Level 1 - High impact

### L1-1 Stream the SH fit instead of storing all spectra

**Status:** `[ ]`  
**Effort:** M  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HrirInterpolatorSH.java`

**Problem**

The loader keeps full complex spectra for every measured direction and both
ears. For the bundled 12,722-direction SOFA this is roughly:

```
2 ears x 12,722 directions x 2 x 1024 floats x 4 bytes ~= 199 MiB
```

Peak memory during load is roughly 300 MB including the SOFA data.

**Change**

- Compute each direction's spectrum once and immediately accumulate the
  per-bin `A^T b` terms, or process directions in bounded chunks.
- Store only the SH basis matrix, the transposed matrix, and the per-bin
  accumulators.
- Keep the public behavior and coefficient layout unchanged.

**Acceptance**

- Peak load memory is substantially lower than the current implementation.
- Fitted coefficients match the current output within numerical tolerance.
- Large SOFA files no longer risk `OutOfMemoryError` on normal Processing
  heap settings.

### L1-2 Fit only the non-negative frequency bins

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HrirInterpolatorSH.java`

**Problem**

The HRIRs are real, so the spectrum is Hermitian-symmetric. The current fit
processes all `fftSize` complex bins, duplicating work and coefficient storage.

**Change**

- Fit bins 0 through `fftSize/2` only.
- Reconstruct the negative-frequency bins by conjugation when needed.
- Keep the interleaved complex layout unless L2-1 replaces it.

**Acceptance**

- Reconstructed spectra are equivalent to the current version.
- Fit time and coefficient memory are approximately halved.

### L1-3 Parallelize the SH fit and HRIR preparation

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HrirInterpolatorSH.java`

**Problem**

The per-bin solves are independent, and the per-direction resample/FFT work is
independent. The current serial fit is the main reason startup takes about
10 seconds.

**Change**

- Parallelize the per-bin solve loop.
- Parallelize HRIR resampling and FFT preparation over directions.
- Use per-thread scratch arrays; avoid shared mutable buffers.
- Keep a deterministic serial fallback for debugging.

**Acceptance**

- Multi-core startup is significantly faster than the current version.
- Results are deterministic and match the serial implementation.
- No data races or shared-state corruption under repeated loads.

### L1-4 Crossfade or smooth direction changes

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

A direction change replaces the cached HRTF spectrum immediately at the next
block boundary. Fast motion can produce zipper/click artifacts.

**Change**

- Keep the previous spectrum and interpolate between old and new over one or
  two blocks.
- Alternatively, smooth the evaluated SH coefficients over time.
- Avoid recomputing for negligible direction changes.

**Acceptance**

- Rapid source motion produces no audible clicks or zipper noise.
- Static-position output and CPU cost remain acceptable.
- A regression test compares output continuity across a direction jump.

## Level 2 - Quality

### L2-1 Real-input FFT / half-spectrum processing

**Status:** `[ ]`  
**Effort:** M  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/FFT1D.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

The current FFT is full-complex and keeps both conjugate halves for real input.
This doubles FFT, multiply, reconstruction, and fit work.

**Change**

- Add a real-input FFT using the standard half-size complex FFT + twiddle
  packing, or a dedicated real FFT implementation.
- Store and reconstruct only bins 0 through `N/2`.
- Keep the current class as a reference implementation for tests.

**Acceptance**

- Output matches the current implementation within tolerance.
- CPU and fit memory are reduced.
- No new dependency is introduced.

### L2-2 Better HRIR resampling

**Status:** `[ ]`  
**Effort:** S  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

HRIRs are resampled with linear interpolation and no anti-alias filter. This is
acceptable at the native rate but weak when downsampling, for example 48 kHz to
44.1 kHz.

**Change**

- Add a small windowed-sinc or polyphase resampler.
- Apply a low-pass stage before decimation.
- Keep linear interpolation as a fallback for identical rates.

**Acceptance**

- Resampled HRIRs have no obvious aliasing above the output Nyquist.
- Startup cost remains acceptable.
- Existing 44.1 kHz behavior is unchanged.

### L2-3 Frequency-split interpolation (research)

**Status:** `[ ]`  
**Effort:** L  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HrirInterpolatorSH.java`,
`src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

Order-5 SH interpolation smooths high-frequency pinna cues. This is good for
artifact-free motion but can blur elevation and externalization.

**Change**

- Investigate SH interpolation for low/mid frequencies and nearest-neighbour or
  crossfaded measured HRIRs for high frequencies.
- Keep this optional and measurable; do not enable it without listening tests.

**Acceptance**

- Objective spectral-distortion metrics improve at high frequencies.
- No clicks or comb-filtering during motion.
- A documented fallback to pure SH interpolation remains available.

### L2-4 Optional normalization / diffuse-field EQ

**Status:** `[ ]`  
**Effort:** M  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

Different SOFA datasets have different overall gain and timbre. SH fitting can
also change energy relative to the measured HRIRs.

**Change**

- Add an optional normalization or diffuse-field equalization stage.
- Keep it off by default or make it explicit in the API.
- Do not hard-code dataset-specific corrections.

**Acceptance**

- Switching between supported SOFA files does not produce large level jumps
  when normalization is enabled.
- Default output is unchanged when the option is off.

### L2-5 Optional fitted-coefficient cache

**Status:** `[ ]`  
**Effort:** M  
**Files:** `src/main/java/com/trackmeifyoucan/hrtf/HRTFProcessor.java`

**Problem**

The SH fit is expensive and repeats for the same SOFA file, sample rate, FFT
size, and order.

**Change**

- Optionally serialize fitted coefficients to a cache file.
- Key the cache by SOFA path/hash, file size and modification time, SH order,
  FFT size, sample rate, and a cache format version.
- Fall back to fitting when the cache is missing or invalid.

**Acceptance**

- Second and subsequent loads are effectively instant.
- A stale or incompatible cache never produces incorrect audio.
- Cache writes fail gracefully on read-only folders.

## Level 3 - Polish

### L3-1 Documentation and examples cleanup

**Status:** `[ ]`  
**Effort:** S  
**Files:** `README.md`, `docs/index.md`, `docs/reference.md`,
`examples/BinauralWithSoundLib`, `examples/BinauralStandalone`

- Fix the "any block size" claim; `process()` requires exactly
  `getBlockSize()` samples.
- Document the one-block plus HRIR-onset latency.
- Document azimuth/elevation convention after L0-2.
- Document that one `HRTF` instance represents one position and that multiple
  sources are summed.
- Document mono versus stereo source behavior.
- Document SOFA sample-rate and `Data.Delay` handling.
- Add a short troubleshooting section for missing `.sofa` files.

### L3-2 Extend the development test harness

**Status:** `[ ]`  
**Effort:** S  
**Files:** `dev/TestHarness.java`

- Keep the existing FFT, convolution, resampling, and correlation checks.
- Add ILD and ITD sign checks at +/-90 degrees.
- Add a spectral-distortion check at measured directions.
- Add a 48 kHz SOFA load test.
- Add a sparse-direction SH-order test.
- Add a direction-jump continuity test.
- Print memory and startup time for the bundled SOFA.

### L3-3 Small API/robustness polish

**Status:** `[ ]`  
**Effort:** S  
**Files:** `HRTF.java`, `HRTFUgen.java`, `BinauralOutput.java`

- Replace the static `newInstanceCount` ear/unit tracking with per-instance
  state.
- Unregister the `pre` callback or provide `dispose()` for sketch restarts.
- Avoid the "audio output failed" message on a normal `BinauralOutput.stop()`.
- Expose block size, latency, IR length, and effective SH order as read-only
  information.
- Avoid the brief one-ear-null window while the loader attaches both renderers.
- Consider clamping or documenting output headroom; do not add a limiter
  unless requested.

## Out of scope

The following are intentionally excluded to keep HRTF focused on binaural
rendering:

- Ambisonics / HOA encoding, rotation, and decoding.
- Room reflections, reverb, occlusion, distance attenuation, near-field.
- Speaker-array decoding and multichannel output.
- Native code, JNI, pffft/SIMD ports, or platform-specific builds.
- A bundled multi-subject HRIR database or online HRIR downloads.
- Visualisation, GUI, or scene-graph features.
- Multi-listener or multi-position-wide rendering beyond separate `HRTF`
  instances.

## Milestones

| Milestone | Contents |
|---|---|
| **1.0.x** | L0-1 through L0-4: correct SOFA handling, azimuth docs, ear-specific rendering, safe SH order |
| **1.1** | L1-1 through L1-4: lower memory, half-spectrum fit, parallel load, smooth motion |
| **1.2** | L2-1, L2-2, L2-5: real FFT, better resampling, optional cache |
| **Backlog** | L2-3, L2-4, L3-1 through L3-3 |

## Verification

For every item:

1. Build with `.\build.ps1`.
2. Run the development harness in `dev/TestHarness.java`.
3. Compare against the previous revision using the same SOFA file and output
   sample rate.
4. Use headphones for subjective checks, especially L0-2, L1-4, L2-2, and
   L2-3.
5. For release builds, run `.\make-release.ps1` and verify the packaged
   examples still find the shared SOFA file.

## Notes and risks

- **SH order vs quality:** Higher order is not automatically better on sparse
  or noisy measurements. Use objective metrics and listening tests.
- **Crossfade cost:** A crossfade needs either a second spectrum or a second
  convolution state; keep it to one or two blocks to avoid memory growth.
- **Cache invalidation:** A wrong cache key can silently produce wrong audio.
  Include file identity, processing parameters, and a format version.
- **Real FFT complexity:** Keep the existing FFT for tests and as a fallback
  until the real FFT is proven equivalent.
- **Stereo sources:** The current Effect path convolves each input channel with
  its own ear. Do not silently change this to a downmix without documenting it
  as a behavior change.
