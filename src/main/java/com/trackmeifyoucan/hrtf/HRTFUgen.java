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

import com.jsyn.ports.UnitInputPort;
import com.jsyn.ports.UnitOutputPort;
import com.jsyn.unitgen.UnitFilter;

/**
 * A JSyn unit that renders one ear (side) of a binaural signal. Instances of
 * this class are created automatically by {@code processing.sound}-style use
 * of {@link HRTF}; you normally do not instantiate it yourself.
 *
 * <p>It sits inside the Sound library's synthesis engine: mono input in,
 * one ear of the HRTF-convolved signal out.</p>
 */
public class HRTFUgen extends UnitFilter {

  /** side index for the left ear */
  public static final int LEFT = 0;
  /** side index for the right ear */
  public static final int RIGHT = 1;

  private volatile HRTFProcessor processor;
  private final int side;

  private double[] fill;
  private int fillPos;

  private float[] mono;
  private double[] blkL;
  private double[] blkR;

  private double[] ring;
  private final int ringSize = 16 * 256;
  private int writePos;
  private int readPos;
  private int count;

  private volatile double amp = 1.0;
  volatile float azimuth = 0;
  volatile float elevation = 0;

  /**
   * @param side {@link #LEFT} or {@link #RIGHT}
   */
  public HRTFUgen(int side) {
    this.side = side;
    this.input = new UnitInputPort("Input");
    this.output = new UnitOutputPort("Output");
    this.addPort(this.input);
    this.addPort(this.output);
  }

  /**
   * Attach the DSP engine to this unit (idempotent for the same instance).
   * Until this is called the unit renders silence. The engine reference is
   * published last, so the audio thread never sees a half-initialized unit.
   */
  public synchronized void setProcessor(HRTFProcessor processor) {
    if (this.processor != processor) {
      fill = new double[processor.getBlockSize()];
      mono = new float[processor.getBlockSize()];
      blkL = new double[processor.getBlockSize()];
      blkR = new double[processor.getBlockSize()];
      ring = new double[ringSize];
      fillPos = 0;
      writePos = 0;
      readPos = 0;
      count = 0;
      this.processor = processor;
    }
  }

  void amp(float amp) {
    this.amp = amp;
  }

  void position(float azimuth, float elevation) {
    this.azimuth = azimuth;
    this.elevation = elevation;
  }

  @Override
  public void generate(int start, int limit) {
    double[] in = input.getValues();
    double[] out = output.getValues();
    double a = amp;

    HRTFProcessor proc = this.processor;
    if (proc == null) {
      for (int i = start; i < limit; i++) {
        out[i] = 0;
      }
      return;
    }
    int bs = proc.getBlockSize();

    for (int i = start; i < limit; i++) {
      fill[fillPos++] = in[i];
      if (fillPos == bs) {
        for (int j = 0; j < bs; j++) {
          mono[j] = (float) fill[j];
        }
        proc.process(mono, blkL, blkR, azimuth, elevation);
        for (int j = 0; j < bs; j++) {
          ring[writePos] = (side == LEFT ? blkL[j] : blkR[j]) * a;
          writePos = (writePos + 1) % ringSize;
          if (count < ringSize) {
            count++;
          } else {
            readPos = (readPos + 1) % ringSize;
          }
        }
        fillPos = 0;
      }
    }

    int n = limit - start;
    int avail = Math.min(n, count);
    for (int i = 0; i < n; i++) {
      if (i < avail) {
        out[start + i] = ring[readPos];
        readPos = (readPos + 1) % ringSize;
      } else {
        out[start + i] = 0;
      }
    }
    count -= avail;
  }
}
