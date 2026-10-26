# Builds the library and creates the two GitHub-release attachments:
#   release\HRTF.zip  (unpacks to a single HRTF folder for the sketchbook)
#   release\HRTF.txt  (copy of library.properties, the stable-URL metadata)
# Attach the files to a release at https://github.com/trackme518/HRTF/releases
# WITHOUT version numbers in the names, then submit the /latest/download/
# URL of HRTF.txt to the Processing librarian.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

$java = if ($env:HRTF_JDK) { $env:HRTF_JDK } else { "C:\Users\leischner\.jdks\jbr-17.0.14" }
$mvn  = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { "mvn" }
$env:JAVA_HOME = $java

& $mvn -q -B -f "$root\pom.xml" clean package
if ($LASTEXITCODE -ne 0) { throw "maven build failed" }

$stage = "$root\release\HRTF"
Remove-Item "$root\release" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$stage\library" | Out-Null
Copy-Item "$root\target\HRTF.jar" "$stage\library\HRTF.jar"
Copy-Item "$root\library.properties" "$stage\library.properties"
if (Test-Path "$root\README.md")   { Copy-Item "$root\README.md" "$stage\README.md" }
if (Test-Path "$root\LICENSE.md")  { Copy-Item "$root\LICENSE.md" "$stage\LICENSE.md" }
Copy-Item "$root\examples" "$stage\examples" -Recurse
if (Test-Path "$root\data") { Copy-Item "$root\data" "$stage\data" -Recurse }

# Build the zip by hand: Compress-Archive on Windows writes entry names with
# backslashes, which unzip tools on macOS/Linux treat as literal filename
# characters, flattening the archive. Zip entries must use forward slashes.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zipPath = "$root\release\HRTF.zip"
$releaseRoot = (Resolve-Path "$root\release").Path
$zipStream = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::Create)
try {
    $zip = [System.IO.Compression.ZipArchive]::new($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $dirs = [System.Collections.Generic.HashSet[string]]::new()
        [void]$dirs.Add("HRTF/")
        [void]$zip.CreateEntry("HRTF/")
        Get-ChildItem -LiteralPath $stage -Recurse -Force | ForEach-Object {
            $entryName = $_.FullName.Substring($releaseRoot.Length + 1).Replace('\', '/')
            if ($_.PSIsContainer) {
                if ($dirs.Add("$entryName/")) {
                    [void]$zip.CreateEntry("$entryName/")
                }
            } else {
                $entry = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
                $out = $entry.Open()
                $in = [System.IO.File]::OpenRead($_.FullName)
                try { $in.CopyTo($out) } finally { $in.Dispose(); $out.Dispose() }
            }
        }
    } finally { $zip.Dispose() }
} finally { $zipStream.Dispose() }

$check = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    if ($check.Entries | Where-Object { $_.FullName.Contains('\') }) {
        throw "HRTF.zip contains backslash entry names"
    }
} finally { $check.Dispose() }

Copy-Item "$root\library.properties" "$root\release\HRTF.txt"
Remove-Item $stage -Recurse -Force

Write-Host "Release attachments ready in $root\release : HRTF.zip, HRTF.txt"
