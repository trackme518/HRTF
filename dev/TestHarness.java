package com.trackmeifyoucan.hrtf;

import java.util.Random;

public class TestHarness {

  static int failures = 0;

  public static void main(String[] args) throws Exception {
    testFFT();
    String sofa = args.length > 0 ? args[0]
        : "C:/Users/leischner/Documents/GitHub/HRTF/binaural4/data/HRIR/KEMAR_s.sofa";
    testConvolution(sofa);
    testResample(sofa);
    System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
    System.exit(failures == 0 ? 0 : 1);
  }

  static void check(boolean cond, String name) {
    System.out.println((cond ? "PASS  " : "FAIL  ") + name);
    if (!cond) {
      failures++;
    }
  }

  static void testFFT() {
    int n = 64;
    FFT1D fft = new FFT1D(n);
    Random rnd = new Random(42);
    double[] a = new double[2 * n];
    for (int i = 0; i < 2 * n; i++) {
      a[i] = rnd.nextDouble() * 2 - 1;
    }

    double[] expected = new double[2 * n];
    for (int k = 0; k < n; k++) {
      double sr = 0, si = 0;
      for (int t = 0; t < n; t++) {
        double ang = -2.0 * Math.PI * k * t / n;
        double c = Math.cos(ang);
        double s = Math.sin(ang);
        sr += a[2 * t] * c - a[2 * t + 1] * s;
        si += a[2 * t] * s + a[2 * t + 1] * c;
      }
      expected[2 * k] = sr;
      expected[2 * k + 1] = si;
    }

    double[] f = a.clone();
    fft.forward(f);
    double maxErr = 0;
    for (int i = 0; i < 2 * n; i++) {
      maxErr = Math.max(maxErr, Math.abs(f[i] - expected[i]));
    }
    check(maxErr < 1e-8, "FFT forward matches naive DFT (err=" + maxErr + ")");

    double[] b = f.clone();
    fft.inverse(b);
    maxErr = 0;
    for (int i = 0; i < 2 * n; i++) {
      maxErr = Math.max(maxErr, Math.abs(b[i] - a[i]));
    }
    check(maxErr < 1e-8, "FFT inverse round-trip (err=" + maxErr + ")");
  }

  static void testConvolution(String sofa) throws Exception {
    long t0 = System.currentTimeMillis();
    HRTFProcessor proc = new HRTFProcessor(sofa, 44100.0);
    System.out.println("processor ready in " + (System.currentTimeMillis() - t0) + " ms, "
        + "blockSize=" + proc.getBlockSize());

    int bs = proc.getBlockSize();
    double[] outL = new double[bs];
    double[] outR = new double[bs];

    // load the measurement IR at front (az 0, el 0) for reference
    SofaHrirSet set = SofaHrirSet.load(sofa);
    int ref = -1;
    for (int i = 0; i < set.azimuths.length; i++) {
      if (Math.abs(set.azimuths[i]) < 0.6f && Math.abs(set.elevations[i]) < 0.6f) {
        ref = i;
        break;
      }
    }
    System.out.println("SOFA: " + set.azimuths.length + " directions, irLen=" + set.irLength
        + ", rate=" + set.sampleRate + (ref >= 0 ? ", ref(front)=" + ref : ", NO FRONT REF"));

    // unit impulse through the engine at az=0,el=0
    proc.setPosition(0, 0);
    double peakL = 0, peakR = 0;
    int peakIdxL = -1;
    float[] impulse = new float[bs];
    impulse[0] = 1f;
    double energy = 0;
    outer:
    for (int block = 0; block < 40; block++) {
      java.util.Arrays.fill(impulse, 0);
      if (block == 1) {
        impulse[0] = 1f;
      }
      proc.process(impulse, outL, outR);
      if (block >= 1) {
        for (int i = 0; i < bs; i++) {
          long g = (long) (block - 1) * bs + i;
          if (Math.abs(outL[i]) > Math.abs(peakL)) {
            peakL = outL[i];
            peakIdxL = (int) g;
          }
          if (Math.abs(outR[i]) > Math.abs(peakR)) {
            peakR = outR[i];
          }
          energy += outL[i] * outL[i] + outR[i] * outR[i];
        }
        if (block > 12) {
          break outer;
        }
      }
    }
    System.out.println("impulse response peak L=" + peakL + " at sample " + peakIdxL
        + ", peak R=" + peakR);
    check(Math.abs(peakL) > 0.02, "impulse produces L response");
    check(Math.abs(peakR) > 0.02, "impulse produces R response");
    check(energy > 1e-6, "impulse energy propagates");

    // shape fidelity: interpolated IR at the measured (0,0) direction should
    // correlate with the measured IR (order-5 SH smooths the sharp direct
    // sound, so compare shape/correlation rather than level)
    double[] interpolatedFrontL = captureIR(proc, set.azimuths[ref], set.elevations[ref], bs, 4);
    double corr = correlation(interpolatedFrontL, set.hrirL[ref]);
    System.out.printf("corr(interp L, measured L) at measured dir = %.3f%n", corr);
    check(corr > 0.5, "interpolated IR correlates with measured IR at measurement direction");

    // lateral asymmetry: source at az=90 should differ clearly between ears
    double diff90 = 0, norm90 = 0;
    proc.setPosition(90, 0);
    java.util.Arrays.fill(impulse, 0);
    impulse[0] = 1f;
    double[] sideL = new double[2 * bs];
    double[] sideR = new double[2 * bs];
    for (int block = 0; block < 2; block++) {
      proc.process(impulse, outL, outR);
      System.arraycopy(outL, 0, sideL, block * bs, bs);
      System.arraycopy(outR, 0, sideR, block * bs, bs);
    }
    for (int i = 0; i < 2 * bs; i++) {
      diff90 += (sideL[i] - sideR[i]) * (sideL[i] - sideR[i]);
      norm90 += sideL[i] * sideL[i];
    }
    check(Math.sqrt(diff90 / norm90) > 0.25,
        "lateral source asymmetric L/R (rel diff=" + Math.sqrt(diff90 / norm90) + ")");

    // realtime budget: how long does one block take (excluding position changes)?
    double[] noiseL = new double[bs];
    double[] noiseR = new double[bs];
    float[] noise = new float[bs];
    Random rnd = new Random(7);
    for (int i = 0; i < bs; i++) {
      noise[i] = (float) (rnd.nextDouble() * 2 - 1);
    }
    long b0 = System.nanoTime();
    for (int block = 0; block < 20000; block++) {
      proc.process(noise, noiseL, noiseR);
    }
    double perBlock = (System.nanoTime() - b0) / 20000.0 / 1e6;
    double budget = 1000.0 * proc.getBlockSize() / 44100.0;
    System.out.printf("block time %.3f ms (realtime budget %.3f ms)%n", perBlock, budget);
    check(perBlock < budget * 0.5, "processing leaves >50% realtime headroom");
  }

  static double[] captureIR(HRTFProcessor proc, float az, float el, int bs, int blocks) {
    proc.setPosition(az, el);
    float[] zero = new float[bs];
    double[] l = new double[bs];
    double[] r = new double[bs];
    for (int b = 0; b < 16; b++) {
      proc.process(zero, l, r); // flush history
    }
    double[] out = new double[blocks * bs];
    float[] in = new float[bs];
    in[0] = 1f;
    for (int b = 0; b < blocks; b++) {
      proc.process(in, l, r);
      System.arraycopy(l, 0, out, b * bs, bs);
      in = new float[bs];
    }
    return out;
  }

  static double correlation(double[] a, float[] b) {
    int n = Math.min(a.length, b.length);
    double ma = 0, mb = 0;
    for (int i = 0; i < n; i++) {
      ma += a[i];
      mb += b[i];
    }
    ma /= n;
    mb /= n;
    double num = 0, da = 0, db = 0;
    for (int i = 0; i < n; i++) {
      double x = a[i] - ma;
      double y = b[i] - mb;
      num += x * y;
      da += x * x;
      db += y * y;
    }
    return num / (Math.sqrt(da * db) + 1e-30);
  }

  static void testResample(String sofa) throws Exception {
    HRTFProcessor proc = new HRTFProcessor(sofa, 48000.0);
    int bs = proc.getBlockSize();
    proc.setPosition(45, 10);
    float[] impulse = new float[bs];
    impulse[0] = 1f;
    double peak = 0;
    double[] l = new double[bs];
    double[] r = new double[bs];
    for (int block = 0; block < 3; block++) {
      java.util.Arrays.fill(impulse, 0);
      if (block == 0) {
        impulse[0] = 1f;
      }
      proc.process(impulse, l, r);
      for (double v : l) {
        peak = Math.max(peak, Math.abs(v));
      }
    }
    check(peak > 0.02, "48 kHz processor works (peak=" + peak + ")");
  }
}
