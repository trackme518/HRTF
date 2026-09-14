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
 * Spherical-harmonic (SH) interpolator for frequency-domain HRIR data.
 *
 * <p>A least-squares set of real SH coefficients is fitted per frequency bin
 * over all measured directions; a spectrum for any (azimuth, elevation)
 * direction is then reconstructed by evaluating the SH basis at that
 * direction. This allows smooth, artifact-free rotation of a virtual sound
 * source between measurement positions.</p>
 */
final class HrirInterpolatorSH {

  private final int order;
  private final int k;

  private double[][] aTaInv;
  private int fftSize;

  private float[][] coeffsRealL;
  private float[][] coeffsImagL;
  private float[][] coeffsRealR;
  private float[][] coeffsImagR;

  /**
   * @param order SH expansion order, 3 = fast/coarse, 5 = medium, 7 = accurate
   */
  HrirInterpolatorSH(int order) {
    this.order = order;
    this.k = (order + 1) * (order + 1);
  }

  /**
   * Fit SH coefficients from frequency-domain HRIR maps.
   *
   * @param azimuths  measurement azimuths in degrees [0,360), length M
   * @param elevations measurement elevations in degrees [-90,90], length M
   * @param specL     left-ear spectra, M x (2 * fftSize), complex interleaved
   * @param specR     right-ear spectra, M x (2 * fftSize), complex interleaved
   * @param fftSize   number of complex frequency bins
   * @throws IllegalStateException if the normal equations cannot be solved
   */
  void fitSH(float[] azimuths, float[] elevations, float[][] specL, float[][] specR, int fftSize) {
    this.fftSize = fftSize;
    int m = azimuths.length;
    if (m == 0) {
      throw new IllegalArgumentException("No measurement directions found for SH fit");
    }
    if (m < k) {
      throw new IllegalArgumentException("Need at least " + k + " measurement directions for order " + order);
    }

    double[][] a = new double[m][k];
    for (int i = 0; i < m; i++) {
      evalRealSHVector(azimuths[i], elevations[i], a[i]);
    }

    double[][] ata = new double[k][k];
    for (int x = 0; x < k; x++) {
      for (int y = x; y < k; y++) {
        double s = 0.0;
        for (int i = 0; i < m; i++) {
          s += a[i][x] * a[i][y];
        }
        ata[x][y] = s;
        ata[y][x] = s;
      }
    }

    aTaInv = invertMatrix(ata);
    if (aTaInv == null) {
      throw new IllegalStateException("Failed to invert ATA matrix for SH fit");
    }

    coeffsRealL = new float[fftSize][k];
    coeffsImagL = new float[fftSize][k];
    coeffsRealR = new float[fftSize][k];
    coeffsImagR = new float[fftSize][k];

    double[][] at = new double[k][m];
    for (int x = 0; x < k; x++) {
      for (int i = 0; i < m; i++) {
        at[x][i] = a[i][x];
      }
    }

    double[] atb = new double[k];
    double[] c = new double[k];

    for (int f = 0; f < fftSize; f++) {
      int ridx = 2 * f;

      solveColumn(at, specL, m, ridx, atb, c);
      for (int x = 0; x < k; x++) {
        coeffsRealL[f][x] = (float) c[x];
      }
      solveColumn(at, specL, m, ridx + 1, atb, c);
      for (int x = 0; x < k; x++) {
        coeffsImagL[f][x] = (float) c[x];
      }
      solveColumn(at, specR, m, ridx, atb, c);
      for (int x = 0; x < k; x++) {
        coeffsRealR[f][x] = (float) c[x];
      }
      solveColumn(at, specR, m, ridx + 1, atb, c);
      for (int x = 0; x < k; x++) {
        coeffsImagR[f][x] = (float) c[x];
      }
    }
  }

  private void solveColumn(double[][] at, float[][] specs, int m, int col,
      double[] atb, double[] out) {
    for (int x = 0; x < k; x++) {
      double s = 0.0;
      double[] row = at[x];
      for (int i = 0; i < m; i++) {
        s += row[i] * specs[i][col];
      }
      atb[x] = s;
    }
    multiplyMatrixVector(aTaInv, atb, out);
  }

  /**
   * Reconstruct the left/right complex spectra for a direction, writing into
   * caller-provided interleaved buffers.
   *
   * @param azDeg  azimuth in degrees [0,360)
   * @param elDeg  elevation in degrees [-90,90]
   * @param specL  output buffer, length 2 * fftSize
   * @param specR  output buffer, length 2 * fftSize
   */
  void reconstructComplexSpectrum(float azDeg, float elDeg, float[] specL, float[] specR) {
    if (fftSize == 0) {
      throw new IllegalStateException("fitSH must be called before reconstructComplexSpectrum");
    }
    double[] y = new double[k];
    evalRealSHVector(azDeg, elDeg, y);

    for (int f = 0; f < fftSize; f++) {
      double reL = 0, imL = 0, reR = 0, imR = 0;
      float[] crl = coeffsRealL[f];
      float[] cil = coeffsImagL[f];
      float[] crr = coeffsRealR[f];
      float[] cir = coeffsImagR[f];
      for (int x = 0; x < k; x++) {
        double yk = y[x];
        reL += crl[x] * yk;
        imL += cil[x] * yk;
        reR += crr[x] * yk;
        imR += cir[x] * yk;
      }
      specL[2 * f] = (float) reL;
      specL[2 * f + 1] = (float) imL;
      specR[2 * f] = (float) reR;
      specR[2 * f + 1] = (float) imR;
    }
  }

  // ---------------- SH basis ----------------

  private void evalRealSHVector(float azDeg, float elDeg, double[] y) {
    double az = Math.toRadians(azDeg);
    double el = Math.toRadians(elDeg);
    double theta = Math.PI / 2.0 - el;
    int idx = 0;
    for (int l = 0; l <= order; l++) {
      for (int m = -l; m <= l; m++) {
        y[idx++] = realSH(l, m, theta, az);
      }
    }
  }

  private double realSH(int l, int m, double theta, double phi) {
    double x = Math.cos(theta);
    int absm = Math.abs(m);
    double plm = associatedLegendre(l, absm, x);
    double nlm = shNormalization(l, absm);
    if (m == 0) {
      return nlm * plm;
    } else if (m > 0) {
      return Math.sqrt(2.0) * nlm * plm * Math.cos(m * phi);
    } else {
      return Math.sqrt(2.0) * nlm * plm * Math.sin(absm * phi);
    }
  }

  private double shNormalization(int l, int m) {
    double num = (2 * l + 1) / (4 * Math.PI);
    double factRatio = factorialRatio(l - m, l + m);
    return Math.sqrt(num * factRatio);
  }

  private static double factorialRatio(int a, int b) {
    double res = 1.0;
    for (int i = a + 1; i <= b; i++) {
      res /= i;
    }
    return res;
  }

  private static double associatedLegendre(int l, int m, double x) {
    double pmm = 1.0;
    if (m > 0) {
      double somx2 = Math.sqrt(1.0 - x * x);
      double fact = 1.0;
      for (int i = 1; i <= m; i++) {
        pmm *= -fact * somx2;
        fact += 2.0;
      }
    }
    if (l == m) {
      return pmm;
    }
    double pmmp1 = x * (2 * m + 1) * pmm;
    if (l == m + 1) {
      return pmmp1;
    }
    double pll = 0.0;
    for (int ll = m + 2; ll <= l; ll++) {
      pll = ((2 * ll - 1) * x * pmmp1 - (ll + m - 1) * pmm) / (ll - m);
      pmm = pmmp1;
      pmmp1 = pll;
    }
    return pmmp1;
  }

  // ---------------- linear algebra ----------------

  private static void multiplyMatrixVector(double[][] mat, double[] v, double[] out) {
    for (int i = 0; i < mat.length; i++) {
      double s = 0.0;
      double[] row = mat[i];
      for (int j = 0; j < v.length; j++) {
        s += row[j] * v[j];
      }
      out[i] = s;
    }
  }

  private static double[][] invertMatrix(double[][] a) {
    int n = a.length;
    double[][] m = new double[n][n];
    double[][] inv = new double[n][n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(a[i], 0, m[i], 0, n);
      inv[i][i] = 1.0;
    }

    for (int i = 0; i < n; i++) {
      int pivot = i;
      double pivAbs = Math.abs(m[i][i]);
      for (int r = i + 1; r < n; r++) {
        double cand = Math.abs(m[r][i]);
        if (cand > pivAbs) {
          pivot = r;
          pivAbs = cand;
        }
      }
      if (pivAbs < 1e-12) {
        return null;
      }

      if (pivot != i) {
        double[] tmp = m[i];
        m[i] = m[pivot];
        m[pivot] = tmp;
        double[] tmp2 = inv[i];
        inv[i] = inv[pivot];
        inv[pivot] = tmp2;
      }

      double diag = m[i][i];
      for (int c = 0; c < n; c++) {
        m[i][c] /= diag;
        inv[i][c] /= diag;
      }

      for (int r = 0; r < n; r++) {
        if (r == i) {
          continue;
        }
        double factor = m[r][i];
        if (factor == 0.0) {
          continue;
        }
        for (int c = 0; c < n; c++) {
          m[r][c] -= factor * m[i][c];
          inv[r][c] -= factor * inv[i][c];
        }
      }
    }
    return inv;
  }
}
