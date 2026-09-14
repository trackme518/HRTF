---
title: HRTF
---

# HRTF

Binaural 3D audio library for [Processing](https://processing.org) 4.
Loads head-related impulse responses from a SOFA file and convolves them
with any sound source, using spherical-harmonic interpolation between
measured directions. Works as an `Effect` on top of the
[Sound library](https://processing.org/reference/libraries/sound/), or
standalone without it.

Written by [Vojtech Leischner](https://trackmeifyoucan.com), 2026.

**Use headphones.** The binaural effect only works over headphones.

## Install

In Processing, open *Sketch > Import Library > Add Library*, find **HRTF**
under Contributions and install it. To install manually, download
`HRTF.zip` from the
[latest release](https://github.com/trackme518/HRTF/releases/latest) and
unzip it into `Documents/Processing/libraries/`, then restart Processing.

The two bundled examples are available from *File > Examples > Libraries > HRTF*:

- **BinauralWithSoundLib** - a looping sample + oscillator routed through
  `hrtf.process(source)`, orbiting a tilted ring so you can hear azimuth
  and elevation change.
- **BinauralStandalone** - the same renderer used without the Sound
  library, driven by a generated test tone.

Both examples share a single bundled `KEMAR_s.sofa` from the library's
`data/` folder (HRIR data is never embedded in the jar; drop any other
`.sofa` file into your sketch's own `data/` folder to use your own).

## Quick start (Sound library)

```java
import com.trackmeifyoucan.hrtf.*;
import processing.sound.*;

HRTF hrtf;
SoundFile file;

void setup() {
  size(600, 600);
  hrtf = new HRTF(this); // loads the first .sofa file in data/ in background
  hrtf.onReady(() -> println("ready"));
  file = new SoundFile(this, "voice_cloned.wav");
  file.loop();
  hrtf.process(file);
}

void draw() {
  hrtf.position(map(mouseX, 0, width, -180, 180), 0); // degrees
}
```

See the [reference](reference/) for the full API.

## Data credits

Example HRIR data: KEMAR SOFA file via
[amini-allight/cipic-hrtf-database](https://github.com/amini-allight/cipic-hrtf-database).

## License

GPL-3.0-or-later, see
[LICENSE](https://github.com/trackme518/HRTF/blob/main/LICENSE.md).
