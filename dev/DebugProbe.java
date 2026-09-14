package com.trackmeifyoucan.hrtf;

public class DebugProbe {
  public static void main(String[] args) throws Exception {
    String sofa = "C:/Users/leischner/Documents/GitHub/HRTF/binaural4/data/HRIR/KEMAR_s.sofa";
    SofaHrirSet set = SofaHrirSet.load(sofa);

    int ref = -1;
    float minScore = Float.MAX_VALUE;
    for (int i = 0; i < set.azimuths.length; i++) {
      float az = set.azimuths[i];
      if (az > 180) {
        az = 360 - az;
      }
      float score = az + Math.abs(set.elevations[i]);
      if (score < minScore) {
        minScore = score;
        ref = i;
      }
    }
    System.out.printf("closest-to-front dir: az=%f el=%f%n", set.azimuths[ref], set.elevations[ref]);
    System.out.printf("measured IR peak L=%f R=%f%n", maxAbs(set.hrirL[ref]), maxAbs(set.hrirR[ref]));

    // distribution of azimuth/elevation values
    float minAz = Float.MAX_VALUE, maxAz = -Float.MAX_VALUE;
    float minEl = Float.MAX_VALUE, maxEl = -Float.MAX_VALUE;
    for (int i = 0; i < set.azimuths.length; i++) {
      minAz = Math.min(minAz, set.azimuths[i]);
      maxAz = Math.max(maxAz, set.azimuths[i]);
      minEl = Math.min(minEl, set.elevations[i]);
      maxEl = Math.max(maxEl, set.elevations[i]);
    }
    System.out.printf("az range [%f,%f] el range [%f,%f]%n", minAz, maxAz, minEl, maxEl);

    // sample some rows around the grid
    for (int i = 0; i < 5; i++) {
      int idx = i * set.azimuths.length / 5;
      System.out.printf("row %d: az=%f el=%f |L|=%f |R|=%f%n", idx, set.azimuths[idx],
          set.elevations[idx], maxAbs(set.hrirL[idx]), maxAbs(set.hrirR[idx]));
    }

    // interpolate exactly at the measured direction and compare
    HRTFProcessor proc = new HRTFProcessor(set, 44100.0);
    proc.setPosition(set.azimuths[ref], set.elevations[ref]);
    int bs = proc.getBlockSize();
    float[] in = new float[bs];
    double[] outL = new double[bs];
    double[] outR = new double[bs];
    in[0] = 1f;
    double mL = 0, mR = 0;
    for (int b = 0; b < 4; b++) {
      proc.process(in, outL, outR);
      for (int i = 0; i < bs; i++) {
        mL = Math.max(mL, Math.abs(outL[i]));
        mR = Math.max(mR, Math.abs(outR[i]));
      }
      in = new float[bs];
    }
    System.out.printf("interpolated IR peak L=%f R=%f%n", mL, mR);
  }

  static double maxAbs(float[] a) {
    double m = 0;
    for (float v : a) {
      m = Math.max(m, Math.abs(v));
    }
    return m;
  }
}
