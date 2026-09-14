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

/**
 * Binaural rendering engine: convolves a mono input signal with HRTF
 * (head-related impulse) filters that are interpolated over direction using
 * spherical harmonics, producing a stereo binaural output.
 *
 * <p>This class is pure Java and does not depend on the Sound library or on
 * Processing itself, so it can be driven manually (see {@link BinauralOutput})
 * or through the {@code processing.sound.HRTF} wrapper.</p>
 *
 * <p>Audio is processed in fixed-size blocks; call {@link #getBlockSize()}
 * for the block size and feed exactly that many samples per call to
 * {@link #process(float[], double[], double[])}.</p>
 */
public class HRTFProcessor {

  /** default SH expansion order used when none is specified */
  public static final int DEFAULT_SH_ORDER = 5;

  private final HrirInterpolatorSH interpolator;
  private final FFT1D fft;
  private final int blockSize;
  private final int fftSize;

  private float[] cachedSpecL;
  private float[] cachedSpecR;
  private float cachedAz = Float.NaN;
  private float cachedEl = Float.NaN;

  private int historyLength;
  private double[] fftBuf;
  private double[] convL;
  private double[] convR;
  private double[] history;

  /** most recently set azimuth in degrees [0,360) */
  public volatile float currentAzimuth = 0;
  /** most recently set elevation in degrees [-90,90] */
  public volatile float currentElevation = 0;

  /**
   * Creates a processor for the given HRIR set, resampled to the requested
   * sample rate, using the default SH order.
   */
  public HRTFProcessor(SofaHrirSet hrirs, double sampleRate) throws IOException {
    this(hrirs, sampleRate, DEFAULT_SH_ORDER);
  }

  /**
   * Creates a processor for the given HRIR set.
   *
   * @param hrirs      HRIRs loaded from a SOFA file
   * @param sampleRate output sample rate in Hz; impulse responses are resampled if needed
   * @param shOrder    spherical-harmonic expansion order (3, 5 or 7)
   */
  public HRTFProcessor(SofaHrirSet hrirs, double sampleRate, int shOrder) throws IOException {
    int irLength = resampleLength(hrirs.irLength, hrirs.sampleRate, sampleRate);
    this.blockSize = 256;
    int size = 1;
    while (size < blockSize + irLength - 1) {
      size <<= 1;
    }
    this.fftSize = size;

    this.fft = new FFT1D(fftSize);
    this.interpolator = new HrirInterpolatorSH(shOrder);
    initBuffers();

    int m = hrirs.hrirL.length;
    float[][] specL = new float[m][];
    float[][] specR = new float[m][];
    double[] timeL = new double[2 * fftSize];
    double[] timeR = new double[2 * fftSize];

    for (int i = 0; i < m; i++) {
      resampleInto(hrirs.hrirL[i], hrirs.sampleRate, sampleRate, timeL, fftSize);
      resampleInto(hrirs.hrirR[i], hrirs.sampleRate, sampleRate, timeR, fftSize);
      fft.forward(timeL);
      fft.forward(timeR);
      specL[i] = toFloat(timeL);
      specR[i] = toFloat(timeR);
    }

    interpolator.fitSH(hrirs.azimuths, hrirs.elevations, specL, specR, fftSize);
  }

  /**
   * Convenience constructor loading the SOFA file directly.
   *
   * @param sofaPath   path to a .sofa file containing HRIRs
   * @param sampleRate output sample rate in Hz
   */
  public HRTFProcessor(String sofaPath, double sampleRate) throws IOException {
    this(SofaHrirSet.load(sofaPath), sampleRate);
  }

  /** Convenience constructor loading a SOFA file with the default SH order at 44100 Hz. */
  public HRTFProcessor(File sofaFile) throws IOException {
    this(SofaHrirSet.load(sofaFile.getAbsolutePath()), 44100.0);
  }

  private HRTFProcessor(HRTFProcessor shared) {
    this.interpolator = shared.interpolator;
    this.fft = shared.fft;
    this.blockSize = shared.blockSize;
    this.fftSize = shared.fftSize;
    initBuffers();
  }

  /**
   * Creates an additional renderer that shares the (immutable) fitted HRIR
   * database with this one but has its own independent convolution state, so
   * several renderers can process the same signal without interfering.
   */
  public HRTFProcessor createSharedRenderer() {
    return new HRTFProcessor(this);
  }

  private void initBuffers() {
    this.historyLength = fftSize - blockSize;
    this.fftBuf = new double[2 * fftSize];
    this.convL = new double[2 * fftSize];
    this.convR = new double[2 * fftSize];
    this.history = new double[historyLength];
    this.cachedSpecL = new float[2 * fftSize];
    this.cachedSpecR = new float[2 * fftSize];
  }

  /** Number of samples per processing block. */
  public int getBlockSize() {
    return blockSize;
  }

  /**
   * Set the virtual source position in polar coordinates. Thread-safe: can be
   * called from draw() while audio blocks are being processed.
   *
   * @param azimuthDegrees   0..360 (0 = front, 90 = left? see note)
   * @param elevationDegrees -90..90 (up positive)
   */
  public void setPosition(float azimuthDegrees, float elevationDegrees) {
    this.currentAzimuth = azimuthDegrees;
    this.currentElevation = elevationDegrees;
  }

  /**
   * Set the virtual source position from a cartesian position around the
   * listener, matching the Processing 3D coordinate system: X = right,
   * Y = down (screen) / up depending on usage; here the same convention as
   * the binaural4 sketch is used: X = forward, Z = right, Y = up.
   */
  public void setPositionXYZ(float x, float y, float z) {
    double azimuth = Math.toDegrees(Math.atan2(z, x));
    double azimuth360 = azimuth < 0 ? azimuth + 360.0 : azimuth;
    double elevation = Math.toDegrees(Math.atan2(y, Math.sqrt(x * x + z * z)));
    setPosition((float) azimuth360, (float) elevation);
  }

  /**
   * Convolve one block of mono input into stereo binaural output.
   *
   * @param monoIn exactly {@link #getBlockSize()} samples
   * @param outL   output left channel, at least {@link #getBlockSize()} long
   * @param outR   output right channel, at least {@link #getBlockSize()} long
   */
  public void process(float[] monoIn, double[] outL, double[] outR) {
    process(monoIn, outL, outR, currentAzimuth, currentElevation);
  }

  /**
   * Convolve one block using an explicit direction instead of the processor's
   * own position fields (useful when several renderers must share one
   * position that is set atomically elsewhere).
   */
  public void process(float[] monoIn, double[] outL, double[] outR, float az, float el) {
    if (az != cachedAz || el != cachedEl) {
      interpolator.reconstructComplexSpectrum(az, el, cachedSpecL, cachedSpecR);
      cachedAz = az;
      cachedEl = el;
    }

    java.util.Arrays.fill(fftBuf, 0);
    for (int i = 0; i < historyLength; i++) {
      fftBuf[2 * i] = history[i];
    }
    for (int i = 0; i < blockSize; i++) {
      fftBuf[2 * (historyLength + i)] = monoIn[i];
    }
    fft.forward(fftBuf);

    for (int i = 0; i < fftSize; i++) {
      int re = 2 * i;
      int im = re + 1;

      double ar = fftBuf[re];
      double ai = fftBuf[im];
      double br = cachedSpecL[re];
      double bi = cachedSpecL[im];
      convL[re] = ar * br - ai * bi;
      convL[im] = ar * bi + ai * br;

      br = cachedSpecR[re];
      bi = cachedSpecR[im];
      convR[re] = ar * br - ai * bi;
      convR[im] = ar * bi + ai * br;
    }

    fft.inverse(convL);
    fft.inverse(convR);

    for (int i = 0; i < blockSize; i++) {
      outL[i] = convL[2 * (historyLength + i)];
      outR[i] = convR[2 * (historyLength + i)];
    }

    if (historyLength > blockSize) {
      System.arraycopy(history, blockSize, history, 0, historyLength - blockSize);
    }
    for (int i = 0; i < blockSize; i++) {
      int pos = historyLength - blockSize + i;
      if (pos >= 0) {
        history[pos] = monoIn[i];
      }
    }
  }

  private static float[] toFloat(double[] d) {
    float[] f = new float[d.length];
    for (int i = 0; i < d.length; i++) {
      f[i] = (float) d[i];
    }
    return f;
  }

  private static int resampleLength(int length, double fromRate, double toRate) {
    if (fromRate <= 0 || fromRate == toRate) {
      return length;
    }
    return (int) Math.ceil(length * toRate / fromRate);
  }

  private static void resampleInto(float[] src, double fromRate, double toRate,
      double[] dest, int destComplexBins) {
    java.util.Arrays.fill(dest, 0);
    if (fromRate <= 0 || fromRate == toRate) {
      for (int i = 0; i < src.length && i < destComplexBins; i++) {
        dest[2 * i] = src[i];
      }
      return;
    }
    int newLength = (int) Math.ceil((double) src.length * toRate / fromRate);
    newLength = Math.min(newLength, destComplexBins);
    for (int i = 0; i < newLength; i++) {
      double pos = i * fromRate / toRate;
      int i0 = (int) pos;
      double frac = pos - i0;
      double v0 = src[Math.min(i0, src.length - 1)];
      double v1 = src[Math.min(i0 + 1, src.length - 1)];
      dest[2 * i] = v0 + (v1 - v0) * frac;
    }
  }
}
