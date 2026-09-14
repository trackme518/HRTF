// Binaural 3D audio with the Processing Sound library.
//
// Two Sound library sources (a looping audio file and a sine oscillator) are
// patched into the HRTF processor the same way effects/envelopes are used,
// and rendered to the stereo output as binaural audio. USE HEADPHONES.
//
// The HRIR database takes ~10 s to load. It loads in the background so the
// sketch stays responsive; isReady()/onReady() tell you when it has finished
// (sources patched before that stay silent until then — they are not lost).
//
// The orbiting ring is tilted against the horizontal plane, so as it rotates
// the source sweeps through all azimuths AND oscillates in elevation.
//
// Mouse: move the source (manual mode). Click toggles orbit/manual.
// Keys:  UP / DOWN  change elevation while in manual mode.

import processing.sound.*;
import com.trackmeifyoucan.hrtf.HRTF;

HRTF hrtf;
SoundFile voice;
SinOsc sine;

PVector source = new PVector(1, 0, 0);
boolean orbit = true;
float elevation = 0;

final float ringTilt = radians(50); // tilt of the orbit ring

void setup() {
  size(640, 420);

  // background load of the SOFA HRIR database (~10 s), sketch not blocked
  // HRIR data is never bundled in the library jar: both examples share the
  // single KEMAR_s.sofa in the library's data folder (see findSofa()).
  // You can drop any .sofa file into your own sketch's data folder instead —
  // or use new HRTF(this), which auto-selects the first .sofa file there.
  hrtf = new HRTF(this, findSofa());
  hrtf.onReady(new Runnable() {
    public void run() {
      println("HRTF is ready — binaural rendering started");
    }
  });

  // any Sound library source can be processed, even before loading finished:
  voice = new SoundFile(this, "voice_cloned.wav");
  voice.amp(0.9);
  voice.loop(); // loop forever
  hrtf.process(voice);

  sine = new SinOsc(this);
  sine.play(220, 0.06);
  hrtf.process(sine);

  // that's it: processed sources automatically reach the speakers through
  // the HRTF (it behaves like an effect sitting in their signal chain)
}

void draw() {
  background(20);

  if (orbit) {
    float a = millis() * 0.0006;
    // tilted circle: azimuth sweeps full 360°, elevation oscillates +/-50°
    // X = forward, Y = up, Z = right
    source.set(cos(a), sin(a) * sin(ringTilt), sin(a) * cos(ringTilt));
  } else {
    // map mouse to a hemisphere in front/around the listener
    float az = map(mouseX, 0, width, -135, 135);
    float a = radians(az);
    source.set(cos(a), sin(radians(elevation)), sin(a) * cos(radians(elevation)));
  }
  hrtf.position(source);

  // --- visualization: top-down view (screen up = forward +X, right = +Z)
  translate(width / 2, height / 2 + 40);
  float r = 140;

  // the tilted ring projects to an ellipse from above
  if (orbit) {
    stroke(90);
    noFill();
    ellipse(0, 0, r * 2 * cos(ringTilt), r * 2);
  }
  stroke(140);
  line(-r - 10, 0, r + 10, 0);
  fill(200);
  noStroke();
  circle(0, 0, 26); // listener's head
  fill(200, 120, 120);
  triangle(0, -16, -6, -6, 6, -6); // nose (+X = up)

  float sx = source.z * r;
  float sy = -source.x * r;
  // dot grows above the listener's ear height, shrinks below
  float el = hrtf.elevation();
  fill(255, 80, 80);
  circle(sx, sy, 20 + el / 90.0 * 10);

  fill(220);
  textAlign(LEFT);
  text("HRTF from Sound library — use headphones", -width / 2 + 20, -height / 2 + 34);
  text("azimuth:  " + nf(hrtf.azimuth(), 3, 1) + "°", -width / 2 + 20, height / 2 - 46);
  text("elevation:" + nf(el, 3, 1) + "°", -width / 2 + 20, height / 2 - 28);
  text(orbit ? "[click: manual mouse position]" : "[click: orbit]", 20, height / 2 - 28);

  if (!hrtf.isReady()) {
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

// Prefer a SOFA file in this sketch's own data folder, else use the shared
// copy shipped with the installed library (examples/<name>/../../data).
String findSofa() {
  File local = dataFile("KEMAR_s.sofa");
  if (local.isFile()) return local.getAbsolutePath();
  File shared = new File(sketchPath("../../data/KEMAR_s.sofa"));
  if (shared.isFile()) return shared.getAbsolutePath();
  return "KEMAR_s.sofa"; // let the library print the proper error
}

void keyPressed() {
  if (keyCode == UP) elevation = constrain(elevation + 10, -90, 90);
  if (keyCode == DOWN) elevation = constrain(elevation - 10, -90, 90);
}
