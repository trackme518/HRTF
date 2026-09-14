# Builds HRTF.jar (with relocated dependencies) and installs the library
# into the Processing sketchbook, ready for Sketch > Import Library.
# Requires: JDK 17, a Maven install (or MAVEN_CMD pointing at mvn.cmd),
# Processing 4.x at PROCESSING_HOME, Sound library in the sketchbook.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# JDK 17+ required (Processing 4 / jhdf). Override with HRTF_JDK if needed.
$java = if ($env:HRTF_JDK) { $env:HRTF_JDK } else { "C:\Users\leischner\.jdks\jbr-17.0.14" }
$mvn  = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { "mvn" }
$sketchbook = "$env:USERPROFILE\Documents\Processing"
$target = "$sketchbook\libraries\HRTF"

$env:JAVA_HOME = $java
& $mvn -q -B -f "$root\pom.xml" clean package
if ($LASTEXITCODE -ne 0) { throw "maven build failed" }

New-Item -ItemType Directory -Force -Path "$target\library" | Out-Null
Copy-Item "$root\target\HRTF.jar" "$target\library\HRTF.jar" -Force
Copy-Item "$root\library.properties" "$target\library.properties" -Force
# copy over, do not delete first (files may be locked by a running sketch)
New-Item -ItemType Directory -Force -Path "$target\examples" | Out-Null
Copy-Item "$root\examples\*" "$target\examples" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$target\data" | Out-Null
Copy-Item "$root\data\*" "$target\data" -Force -ErrorAction SilentlyContinue

Write-Host "Installed to $target"
