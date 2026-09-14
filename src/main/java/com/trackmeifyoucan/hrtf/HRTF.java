/*
 * HRTF - Binaural 3D audio library for Processing
 * Copyright (C) 2026 Vojtech Leischner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package com.trackmeifyoucan.hrtf;

import java.io.File;
import java.io.IOException;

import processing.core.PApplet;
import processing.core.PVector;
import processing.sound.Effect;
import processing.sound.Sound;
import processing.sound.SoundObject;

/**
 * Binaural 3D audio for the Processing Sound library: convolves sources with
 * head-related impulse responses loaded from a SOFA file and places them
 * anywhere around the listener, rendering a stereo binaural signal (use
 * headphones).
 *
 * <p>It is an {@code Effect}, so it is patched into the signal chain exactly
 * like a reverb or filter:</p>
 *
 * <pre>
 * import processing.sound.*;
 * import com.trackmeifyoucan.hrtf.HRTF;
 *
 * HRTF hrtf;
 * SinOsc sine;
 *
 * void setup() {
 *   // loading the HRIR database takes ~10 seconds; it runs in the
 *   // background, isReady()/onReady() tell you when it is done
 *   hrtf = new HRTF(this, "KEMAR_s.sofa");
 *   hrtf.onReady(() -> println("HRTF ready"));
 *   sine = new SinOsc(this);
 *   sine.play(220, 0.5);
 *   hrtf.process(sine);                      // the sine now reaches the
 *                                            // speakers only via the HRTF
 * }
 *
 * void draw() {
 *   if (!hrtf.isReady()) return;             // silent until then, never blocked
 *   hrtf.position(millis() * 0.036f % 360, 0);
 * }
 * </pre>
 *
 * <p>Coordinate convention for {@link #position(PVector)}: listener at the
 * origin, X = forward, Z = right, Y = up (same as Processing's 3D space with
 * Z swapped to the right, as used in the binaural4 sketch).</p>
 *
 * <p>Several sources processed by the same HRTF instance are summed before
 * convolution (like several sounds sharing one reverb). If a source is
 * stopped with its own stop() method, the effect is detached from it by the
 * Sound library; call process() for that source again to re-attach.</p>
 *
 * @author Vojtech Leischner, 2026 — https://trackmeifyoucan.com
 * @webref HRTF
 */
public class HRTF extends Effect<HRTFUgen> {

  // the Effect base class calls newInstance() from its own constructor, twice:
  // first for the left unit, then for the right one. These fields carry no
  // initializers so that the assignments made from newInstance() (which runs
  // during super()) survive subclass initialization.
  private static int newInstanceCount = 0;

  private HRTFUgen firstUnit;
  private HRTFUgen secondUnit;

  private volatile HRTFProcessor processor;
  private volatile boolean ready = false;
  private volatile boolean loadFailed = false;
  private final java.util.concurrent.CopyOnWriteArrayList<Runnable> readyCallbacks =
      new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

  private volatile float azimuth = 0;
  private volatile float elevation = 0;

  /**
   * Creates the HRTF processor, loading the first .sofa file found in the
   * sketch's data folder.
   *
   * @param parent PApplet: typically use "this"
   * @webref HRTF:HRTF
   */
  public HRTF(PApplet parent) {
    this(parent, null);
  }

  /**
   * Creates the HRTF processor from a specific SOFA file.
   *
   * @param parent   PApplet: typically use "this"
   * @param sofaPath path to a .sofa file, either absolute or relative to the
   *                 sketch's data folder (e.g. "KEMAR_s.sofa")
   * @webref HRTF:HRTF
   */
  public HRTF(PApplet parent, String sofaPath) {
    super(parent);

    File file = resolveSofa(parent, sofaPath);
    if (file == null) {
      System.err.println("HRTF: no .sofa file found. Pass a file name to the HRTF() "
          + "constructor or put a .sofa file into the sketch's data folder.");
      loadFailed = true;
      return;
    }

    // Loading + spherical-harmonic fitting of a full HRIR database takes
    // roughly 10 seconds, so it runs in the background and the sketch stays
    // responsive. Sources patched in before loading finishes are silent, not
    // lost: as soon as the database is ready they start rendering through
    // the HRTF. Use isReady() or onReady() to know exactly when that is.
    final int sampleRate = Sound.sampleRate();
    System.out.println("HRTF: loading HRIRs from " + file.getAbsolutePath()
        + " (in background, takes a few seconds)...");
    final long t0 = System.currentTimeMillis();
    Thread loader = new Thread(new Runnable() {
      public void run() {
        try {
          HRTFProcessor p = new HRTFProcessor(file.getAbsolutePath(), sampleRate);
          // attaching the renderers starts the audio path; order matters
          // (setProcessor publishes the engine to the audio thread)
          firstUnit.setProcessor(p.createSharedRenderer());
          secondUnit.setProcessor(p.createSharedRenderer());
          processor = p;
          ready = true;
          System.out.println("HRTF: ready in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (IOException e) {
          System.err.println("HRTF: could not load SOFA file: " + e);
          loadFailed = true;
        }
      }
    }, "HRTF-loader");
    loader.setDaemon(true);
    loader.start();

    // fires onReady callbacks on the Processing thread, right before a frame
    parent.registerMethod("pre", new ReadyNotifier());
  }

  /**
   * Registers a callback that runs when the HRIR database has finished
   * loading and audio rendering becomes active. The callback is executed on
   * the Processing thread (start of the next frame). If the database is
   * already loaded, the callback runs immediately on the calling thread.
   * Callbacks are not invoked if loading failed (see {@link #isReady()}).
   *
   * @param callback code to run when the HRTF is ready
   * @webref HRTF:HRTF
   */
  public void onReady(Runnable callback) {
    if (ready) {
      callback.run();
    } else if (!loadFailed) {
      readyCallbacks.add(callback);
    }
  }

  /**
   * Check whether the HRIR database has finished loading and the HRTF is
   * rendering. Until this returns true (and unless {@link #onReady} fires),
   * processed sources are silent.
   *
   * @return true when the HRTF is rendering
   * @webref HRTF:HRTF
   */
  public boolean isReady() {
    return ready;
  }

  /** Dispatches queued onReady callbacks on the Processing thread. */
  public final class ReadyNotifier {
    public void pre() {
      if (ready && !readyCallbacks.isEmpty()) {
        for (Runnable callback : readyCallbacks) {
          callback.run();
        }
        readyCallbacks.clear();
      }
    }
  }

  private static File resolveSofa(PApplet parent, String sofaPath) {
    if (sofaPath != null) {
      File f = new File(sofaPath);
      if (f.isFile()) {
        return f;
      }
      File data = parent.dataFile(sofaPath);
      if (data.isFile()) {
        return data;
      }
      return null;
    }
    return SofaHrirSet.findFirstSofa(parent.dataFile(""));
  }

  @Override
  protected HRTFUgen newInstance() {
    HRTFUgen unit = new HRTFUgen((newInstanceCount++ % 2 == 0) ? HRTFUgen.LEFT : HRTFUgen.RIGHT);
    if (firstUnit == null) {
      firstUnit = unit;
    } else {
      secondUnit = unit;
    }
    return unit;
  }

  /**
   * Move the virtual source position, in degrees.
   *
   * @param azimuth   0..360 (0 = front, 90 = left, 180 = back)
   * @param elevation -90 (below) .. 90 (above)
   * @webref HRTF:HRTF
   */
  public void position(float azimuth, float elevation) {
    float az = ((azimuth % 360f) + 360f) % 360f;
    this.azimuth = az;
    this.elevation = elevation;
    firstUnit.position(az, elevation);
    secondUnit.position(az, elevation);
  }

  /**
   * Move the virtual source position using a position vector around the
   * listener (X forward, Z right, Y up).
   *
   * @param pos position relative to the listener
   * @webref HRTF:HRTF
   */
  public void position(PVector pos) {
    double az = Math.toDegrees(Math.atan2(pos.z, pos.x));
    if (az < 0) {
      az += 360.0;
    }
    double el = Math.toDegrees(Math.atan2(pos.y, Math.sqrt(pos.x * pos.x + pos.z * pos.z)));
    position((float) az, (float) el);
  }

  /**
   * Change the output level of the binaural rendering.
   *
   * @param amp output amplitude, 0.0 .. 1.0
   * @webref HRTF:HRTF
   */
  public void amp(float amp) {
    firstUnit.amp(amp);
    secondUnit.amp(amp);
  }

  /**
   * Get the current azimuth of the virtual source in degrees.
   *
   * @return current azimuth, 0..360
   */
  public float azimuth() {
    return azimuth;
  }

  /**
   * Get the current elevation of the virtual source in degrees.
   *
   * @return current elevation, -90..90
   */
  public float elevation() {
    return elevation;
  }

  /**
   * For advanced users: the underlying DSP engine, which can also be used
   * independently of the Sound library (see the BinauralStandalone example).
   */
  public HRTFProcessor getProcessor() {
    return processor;
  }
}
