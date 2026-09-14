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
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import io.jhdf.HdfFile;
import io.jhdf.api.Attribute;
import io.jhdf.api.Dataset;

/**
 * Loads head-related impulse responses (HRIRs) from a SOFA (HDF5) file.
 *
 * <p>Reads {@code Data.IR}, {@code SourcePosition} and {@code Global/SampleRate}
 * from a General_FIR type SOFA file as produced by common HRIR measurements
 * (e.g. the SADIE II KEMAR data set). Azimuths are returned in degrees in
 * [0, 360), elevations in degrees in [-90, 90].</p>
 */
public final class SofaHrirSet {

  /** azimuth in degrees [0,360), length M */
  public final float[] azimuths;
  /** elevation in degrees [-90,90], length M */
  public final float[] elevations;
  /** left-ear impulse responses, M x N, normalized */
  public final float[][] hrirL;
  /** right-ear impulse responses, M x N, normalized */
  public final float[][] hrirR;
  /** sample rate of the stored impulse responses in Hz */
  public final double sampleRate;
  /** number of stored impulse response taps */
  public final int irLength;

  private SofaHrirSet(float[] azimuths, float[] elevations, float[][] hrirL, float[][] hrirR,
      double sampleRate, int irLength) {
    this.azimuths = azimuths;
    this.elevations = elevations;
    this.hrirL = hrirL;
    this.hrirR = hrirR;
    this.sampleRate = sampleRate;
    this.irLength = irLength;
  }

  /**
   * Loads an HRIR set from a SOFA file.
   *
   * @param path path to a .sofa file
   * @return the loaded HRIR set
   * @throws IOException if the file cannot be read or lacks the expected SOFA datasets
   */
  public static SofaHrirSet load(String path) throws IOException {
    try (HdfFile sofaFile = new HdfFile(Paths.get(path))) {
      Dataset dataIR = sofaFile.getDatasetByPath("Data.IR");
      Object irData = dataIR.getData();
      double[][][] ir;
      if (irData instanceof double[][][]) {
        ir = (double[][][]) irData;
      } else if (irData instanceof float[][][]) {
        ir = toDouble((float[][][]) irData);
      } else {
        throw new IOException("Unexpected Data.IR layout: " + irData.getClass());
      }

      Dataset sourcePos = sofaFile.getDatasetByPath("SourcePosition");
      double[][] sp = (double[][]) sourcePos.getData();
      boolean radians = isRadians(sourcePos);

      double sampleRate = 44100.0;
      try {
        Dataset sr = sofaFile.getDatasetByPath("Global/SampleRate");
        Object rate = sr == null ? null : sr.getData();
        if (rate instanceof double[]) {
          sampleRate = ((double[]) rate)[0];
        } else if (rate instanceof double[][]) {
          sampleRate = ((double[][]) rate)[0][0];
        } else if (rate instanceof float[]) {
          sampleRate = ((float[]) rate)[0];
        }
      } catch (RuntimeException notFound) {
        // some files store it as a file-level attribute instead
        Attribute attr = sofaFile.getAttributes().get("SampleRate");
        if (attr != null && attr.getData() instanceof Number) {
          sampleRate = ((Number) attr.getData()).doubleValue();
        }
      }

      int m = ir.length;
      int n = ir[0][0].length;
      if (ir[0].length < 2) {
        throw new IOException("SOFA file does not contain two receiver channels (left/right)");
      }

      LinkedHashMap<Long, int[]> unique = new LinkedHashMap<>();
      for (int i = 0; i < m; i++) {
        double az = sp[i][0];
        double el = sp[i][1];
        if (radians) {
          az = Math.toDegrees(az);
          el = Math.toDegrees(el);
        }
        az = ((az % 360.0) + 360.0) % 360.0;
        int azKey = (int) Math.round(az * 100.0);
        int elKey = (int) Math.round(el * 100.0);
        unique.putIfAbsent((long) azKey * 100000L + elKey, new int[] { i });
      }

      int count = unique.size();
      float[] azimuths = new float[count];
      float[] elevations = new float[count];
      float[][] hrirL = new float[count][];
      float[][] hrirR = new float[count][];

      int idx = 0;
      for (int[] first : unique.values()) {
        int i = first[0];
        double az = sp[i][0];
        double el = sp[i][1];
        if (radians) {
          az = Math.toDegrees(az);
          el = Math.toDegrees(el);
        }
        azimuths[idx] = (float) (((az % 360.0) + 360.0) % 360.0);
        elevations[idx] = (float) el;
        hrirL[idx] = new float[n];
        hrirR[idx] = new float[n];
        for (int k = 0; k < n; k++) {
          hrirL[idx][k] = (float) ir[i][0][k];
          hrirR[idx][k] = (float) ir[i][1][k];
        }
        idx++;
      }

      return new SofaHrirSet(azimuths, elevations, hrirL, hrirR, sampleRate, n);
    }
  }

  /** Returns the first .sofa file found in the given folder, or null. */
  public static File findFirstSofa(File folder) {
    File[] files = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".sofa"));
    if (files == null || files.length == 0) {
      return null;
    }
    return files[0];
  }

  private static boolean isRadians(Dataset sourcePos) {
    Map<String, Attribute> attributes = sourcePos.getAttributes();
    if (attributes == null) {
      return false;
    }
    Attribute units = attributes.get("units");
    if (units == null) {
      return false;
    }
    return stringify(units.getData()).toLowerCase().contains("rad");
  }

  private static String stringify(Object data) {
    if (data instanceof Object[]) {
      StringBuilder sb = new StringBuilder();
      for (Object o : (Object[]) data) {
        sb.append(o).append(' ');
      }
      return sb.toString();
    }
    return String.valueOf(data);
  }

  private static double[][][] toDouble(float[][][] src) {
    double[][][] out = new double[src.length][][];
    for (int i = 0; i < src.length; i++) {
      out[i] = new double[src[i].length][];
      for (int j = 0; j < src[i].length; j++) {
        out[i][j] = new double[src[i][j].length];
        for (int k = 0; k < src[i][j].length; k++) {
          out[i][j][k] = src[i][j][k];
        }
      }
    }
    return out;
  }
}
