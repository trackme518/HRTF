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

Run `make-release.ps1` and unzip `HRTF.zip` into
`Documents/Processing/libraries/`, then restart Processing. The two
bundled examples are then available from *File > Examples > Libraries > HRTF*.

## Examples

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

HRIR loading takes ~10 s in the background; the audio stays silent until
`isReady()` returns true.

## Building from source

Requires JDK 17, Maven, Processing 4 and the Sound library installed.
Edit the `processing.home` / `sketchbook.home` paths in `pom.xml` if your
installation differs, then:

```
.\build.ps1        # builds target\HRTF.jar, installs into the sketchbook
.\make-release.ps1 # creates release\HRTF.zip + release\HRTF.txt
```

All runtime dependencies (SOFA reader, etc.) are shaded and relocated
into the jar, so the library cannot clash with other libraries on the
classpath.

## Releasing

1. Bump `version` / `prettyVersion` in `library.properties`.
2. Run `make-release.ps1`, commit and push.
3. On GitHub create a release (e.g. tag `v1.0.0`) and attach
   `HRTF.zip` and `HRTF.txt` (keep these exact, version-free names).
4. Submit the stable URL
   `https://github.com/trackme518/HRTF/releases/latest/download/HRTF.txt`
   to the Processing librarian.

## Data credits

Example HRIR data: KEMAR SOFA file via
[amini-allight/cipic-hrtf-database](https://github.com/amini-allight/cipic-hrtf-database).

## License

HRTF - Binaural 3D audio library for Processing
Copyright (C) 2026 Vojtech Leischner

GPL-3.0-or-later, see [LICENSE.md](LICENSE.md).
