---
title: Reference
---

# HRTF class reference

Package: `com.trackmeifyoucan.hrtf`

```java
import com.trackmeifyoucan.hrtf.*;
```

HRIR loading (read + spherical-harmonic fit) takes about 10 seconds and
runs on a background thread. Until `isReady()` returns true the output is
silent; calling `position()` or `process()` before that is safe - settings
are kept and rendering starts as soon as the data is loaded.

## Constructors

### HRTF(parent)

```java
HRTF hrtf = new HRTF(this);
```

Creates the processor and auto-selects the first `.sofa` file found in the
sketch's data folder. HRIR data is never bundled inside the library jar.

### HRTF(parent, sofaFile)

```java
HRTF hrtf = new HRTF(this, "KEMAR_s.sofa");
```

`sofaFile` is either an absolute path or a file name inside the sketch's
data folder.

## Methods

### process()

```java
hrtf.process(soundFile);   // SoundFile, SinOsc, Noise, SndInput, ...
```

Routes any Sound library source through the binaural renderer (inherited
from `processing.sound.Effect`). Several sources can be processed at once.

### position()

```java
hrtf.position(azimuth, elevation);   // degrees; az 0 = front, 90 = left
hrtf.position(new PVector(x, y, z)); // X forward, Y up, Z right
```

Sets the apparent direction of the sound.

### amp()

```java
hrtf.amp(0.5f);   // 0.0 - 1.0
```

Overall level of the binaural output.

### azimuth() / elevation()

```java
float a = hrtf.azimuth();     // degrees
float e = hrtf.elevation();   // degrees
```

Current direction, as set by `position()`.

### isReady()

```java
if (hrtf.isReady()) { ... }
```

True once the HRIR data has finished loading in the background.

### onReady()

```java
hrtf.onReady(() -> println("binaural rendering started"));
```

Runs the callback on the Processing thread when loading finishes
(immediately if already ready, skipped if loading failed).

### getProcessor()

```java
HRTFProcessor p = hrtf.getProcessor();
```

Gives access to the underlying renderer, e.g. to share one set of HRIRs
between several effects (see `createSharedRenderer()`).

## Standalone use (without the Sound library)

For non-realtime rendering or custom audio I/O:

```java
HRTFProcessor hrtf = new HRTFProcessor(dataPath("KEMAR_s.sofa"), 44100);
hrtf.setPosition(45, 10);            // degrees
hrtf.process(monoIn, outL, outR);    // overlap-save convolution, any block size
```

`BinauralOutput` wraps a `HRTFProcessor` and a `MonoSource` callback in a
live output stream (`start()` / `stop()` / `isRunning()`); see the
BinauralStandalone example.
