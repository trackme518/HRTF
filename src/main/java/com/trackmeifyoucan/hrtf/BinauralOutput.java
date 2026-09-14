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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Minimal standalone binaural playback thread for using the HRTF processor
 * <em>without</em> the Processing Sound library. It repeatedly asks a
 * {@link MonoSource} for one block of mono samples, renders it through an
 * {@link HRTFProcessor} and writes the stereo result to the system default
 * output device.
 */
public class BinauralOutput {

  /**
   * Callback interface for providing the mono input signal, e.g. samples
   * synthesized in code or decoded from any source.
   */
  public interface MonoSource {
    /**
     * Fill the given buffer with exactly buffer.length mono samples in the
     * range -1.0 .. 1.0.
     */
    void next(float[] buffer);
  }

  private final HRTFProcessor processor;
  private final MonoSource source;
  private final int sampleRate;
  private Thread thread;
  private volatile boolean running;
  private SourceDataLine line;

  /**
   * Creates an output driving the processor at 44100 Hz, 16 bit PCM stereo.
   */
  public BinauralOutput(HRTFProcessor processor, MonoSource source) {
    this(processor, source, 44100);
  }

  /**
   * Creates an output driving the processor at the given sample rate.
   * The processor must have been created with the same sample rate.
   */
  public BinauralOutput(HRTFProcessor processor, MonoSource source, int sampleRate) {
    this.processor = processor;
    this.source = source;
    this.sampleRate = sampleRate;
  }

  /** Starts the audio thread. */
  public void start() {
    if (running) {
      return;
    }
    running = true;
    thread = new Thread(this::renderLoop, "HRTF-BinauralOutput");
    thread.setDaemon(true);
    thread.start();
  }

  /** Stops the audio thread. */
  public void stop() {
    running = false;
    if (line != null) {
      line.close();
    }
  }

  /** @return true while the audio thread is active */
  public boolean isRunning() {
    return running;
  }

  private void renderLoop() {
    int blockSize = processor.getBlockSize();
    float[] mono = new float[blockSize];
    double[] outL = new double[blockSize];
    double[] outR = new double[blockSize];
    byte[] bytes = new byte[blockSize * 4];

    try {
      AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
      line = (SourceDataLine) AudioSystem.getLine(
          new DataLine.Info(SourceDataLine.class, format));
      line.open(format, blockSize * 8);
      line.start();

      while (running) {
        source.next(mono);
        processor.process(mono, outL, outR);

        int b = 0;
        for (int i = 0; i < blockSize; i++) {
          short sL = (short) Math.max(-32768, Math.min(32767, Math.round(outL[i] * 32767)));
          short sR = (short) Math.max(-32768, Math.min(32767, Math.round(outR[i] * 32767)));
          bytes[b++] = (byte) (sL & 0xff);
          bytes[b++] = (byte) ((sL >> 8) & 0xff);
          bytes[b++] = (byte) (sR & 0xff);
          bytes[b++] = (byte) ((sR >> 8) & 0xff);
        }
        line.write(bytes, 0, bytes.length);
      }
      line.close();
    } catch (Exception e) {
      System.err.println("HRTF: audio output failed: " + e);
    }
  }
}
