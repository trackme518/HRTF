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

/**
 * In-place iterative radix-2 complex FFT on interleaved double arrays
 * (re, im, re, im, ...). Self-contained so the library does not need to
 * ship a third-party FFT dependency.
 */
final class FFT1D {

  private final int n;
  private final int[] bitRev;
  private final double[] cosTable;
  private final double[] sinTable;

  FFT1D(int size) {
    if (size < 2 || (size & (size - 1)) != 0) {
      throw new IllegalArgumentException("FFT size must be a power of two, got " + size);
    }
    this.n = size;

    int bits = Integer.numberOfTrailingZeros(size);
    bitRev = new int[size];
    for (int i = 0; i < size; i++) {
      int r = 0;
      int x = i;
      for (int b = 0; b < bits; b++) {
        r = (r << 1) | (x & 1);
        x >>>= 1;
      }
      bitRev[i] = r;
    }

    cosTable = new double[size / 2];
    sinTable = new double[size / 2];
    for (int k = 0; k < size / 2; k++) {
      cosTable[k] = Math.cos(2.0 * Math.PI * k / size);
      sinTable[k] = Math.sin(2.0 * Math.PI * k / size);
    }
  }

  /**
   * Forward complex FFT (unscaled).
   *
   * @param a interleaved complex array of length 2 * n
   */
  void forward(double[] a) {
    transform(a, false);
  }

  /**
   * Inverse complex FFT, scaled by 1/n.
   *
   * @param a interleaved complex array of length 2 * n
   */
  void inverse(double[] a) {
    transform(a, true);
  }

  private void transform(double[] a, boolean inverse) {
    for (int i = 0; i < n; i++) {
      int j = bitRev[i];
      if (j > i) {
        double tr = a[2 * i];
        double ti = a[2 * i + 1];
        a[2 * i] = a[2 * j];
        a[2 * i + 1] = a[2 * j + 1];
        a[2 * j] = tr;
        a[2 * j + 1] = ti;
      }
    }

    double sign = inverse ? 1.0 : -1.0;
    for (int len = 2; len <= n; len <<= 1) {
      int step = n / len;
      for (int start = 0; start < n; start += len) {
        for (int k = 0; k < len / 2; k++) {
          int i0 = 2 * (start + k);
          int i1 = i0 + len;
          double wc = cosTable[k * step];
          double ws = sign * sinTable[k * step];
          double ar = a[i0];
          double ai = a[i0 + 1];
          double xr = a[i1];
          double xi = a[i1 + 1];
          double tr = xr * wc - xi * ws;
          double ti = xr * ws + xi * wc;
          a[i0] = ar + tr;
          a[i0 + 1] = ai + ti;
          a[i1] = ar - tr;
          a[i1 + 1] = ai - ti;
        }
      }
    }

    if (inverse) {
      double scale = 1.0 / n;
      for (int i = 0; i < 2 * n; i++) {
        a[i] *= scale;
      }
    }
  }
}
