// Binaural 3D audio WITHOUT the Processing Sound library.
//
// A mono signal generated in code is rendered through HRTFProcessor using
// the library's own javax.sound playback thread (BinauralOutput). USE
// HEADPHONES.
//
// The HRIR database takes ~10 s to load. HRTFProcessor itself is a plain
// synchronous object, so the sketch does the loading on a background thread
// and only starts the audio output when it is done — the sketch never blocks.
//
// The orbiting ring is tilted against the horizontal plane, so as it rotates
// the source sweeps through all azimuths AND oscillates in elevation.
//
// Click toggles orbit / mouse-position mode. In manual mode: mouse X sets
// azimuth, mouse Y sets elevation.

import com.trackmeifyoucan.hrtf.*;
import java.io.IOException;

HRTFProcessor hrtf;
BinauralOutput output;
volatile boolean hrtfReady = false;

boolean orbit = true;
PVector source = new PVector(1, 0, 0);

final float ringTilt = radians(50); // tilt of the orbit ring

void setup() {
  size(640, 420);

  // background load — setup() returns immediately, the HUD shows progress
  Thread loader = new Thread(new Runnable() {
    public void run() {
      try {
        // load the HRIR database (~10 s) matching the output sample rate
        // HRIR data is never bundled with the library: both examples share
        // the single KEMAR_s.sofa in the library's data folder (findSofa());
        // point the HRTFProcessor at any .sofa file you like instead.
        HRTFProcessor p = new HRTFProcessor(findSofa(), 44100);
        // BinauralOutput pulls mono blocks from any MonoSource
        // implementation and writes binaural stereo to the default output.
        BinauralOutput o = new BinauralOutput(p, new TestTone());
        o.start();
        hrtf = p;
        output = o;
        hrtfReady = true;
        println("HRTF is ready — binaural rendering started");
      }
      catch (IOException e) {
        e.printStackTrace();
      }
    }
  }, "HRTF-loader");
  loader.setDaemon(true);
  loader.start();
}

// Prefer a SOFA file in this sketch's own data folder, else use the shared
// copy shipped with the installed library (examples/<name>/../../data).
String findSofa() {
  File local = dataFile("KEMAR_s.sofa");
  if (local.isFile()) return local.getAbsolutePath();
  File shared = new File(sketchPath("../../data/KEMAR_s.sofa"));
  if (shared.isFile()) return shared.getAbsolutePath();
  return "KEMAR_s.sofa"; // let the library print the proper error
}

void draw() {
  background(20);

  float az, el;
  if (orbit) {
    az = (millis() * 0.036f) % 360;
    // tilted ring: elevation oscillates as the ring rotates
    el = degrees(asin(sin(radians(az)) * sin(ringTilt)));
  } else {
    az = map(mouseX, 0, width, -135, 135);
    el = map(mouseY, height, 0, -80, 80);
  }
  if (hrtfReady) {
    hrtf.setPosition(az, el);
  }

  float a = radians(az);
  float e = radians(el);
  source.set(cos(a) * cos(e), sin(e), sin(a) * cos(e));

  // top-down view
  translate(width / 2, height / 2 + 40);
  float r = 140;
  if (orbit) {
    // the tilted ring projects to an ellipse from above
    stroke(90);
    noFill();
    ellipse(0, 0, r * 2 * cos(ringTilt), r * 2);
  }
  stroke(140);
  line(-r - 10, 0, r + 10, 0);
  fill(200);
  noStroke();
  circle(0, 0, 26);
  fill(200, 120, 120);
  triangle(0, -16, -6, -6, 6, -6);
  float sx = source.z * r;
  float sy = -source.x * r;
  fill(255, 80, 80);
  circle(sx, sy, 20 + el / 90.0 * 10);

  fill(220);
  textAlign(LEFT);
  text("HRTF without the Sound library — use headphones", -width / 2 + 20, -height / 2 + 34);
  text("azimuth:   " + nf(az, 3, 1) + "°", -width / 2 + 20, height / 2 - 46);
  text("elevation: " + nf(el, 3, 1) + "°", -width / 2 + 20, height / 2 - 28);
  text(orbit ? "[click: manual mouse position]" : "[click: orbit]", 20, height / 2 - 28);

  if (!hrtfReady) {
    noStroke();
    fill(0, 170);
    rect(-180, -24, 360, 48, 8);
    fill(255);
    textAlign(CENTER);
    text("loading HRIR database…", 0, -2);
    text("(this takes a few seconds)", 0, 18);
  }
}

void mousePressed() {
  orbit = !orbit;
}

// A little melody with vibrato, produced sample-by-sample in code. Replace
// this with your own signal source (decoded audio, synthesis, network...).
class TestTone implements BinauralOutput.MonoSource {
  final float[] melody = { 261.63f, 329.63f, 392.00f, 523.25f, 392.00f, 329.63f };
  final float noteLength = 0.45; // seconds
  float t = 0;
  float phase = 0;

  @Override
    public void next(float[] buffer) {
    float dt = 1.0f / 44100.0f;
    for (int i = 0; i < buffer.length; i++) {
      int note = (int) (t / noteLength) % melody.length;
      float tn = t % noteLength;
      // simple attack/release envelope
      float env = min(1, tn * 20) * min(1, (noteLength - tn) * 12);
      float freq = melody[note] * (1 + 0.006 * sin(TWO_PI * 5.5f * t));
      phase += TWO_PI * freq * dt;
      buffer[i] = 0.5 * env * (sin(phase) + 0.3f * sin(2 * phase));
      t += dt;
    }
  }
}
