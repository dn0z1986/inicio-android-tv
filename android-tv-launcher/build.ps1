# Compila la pantalla de inicio sin Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Resultado: build\Inicio.apk
$ErrorActionPreference = 'Stop'
$tools = "$env:LOCALAPPDATA\hy300-tools"
$env:JAVA_HOME = "$tools\jdk17"
$env:Path = "$tools\jdk17\bin;$env:Path"
$bt = "$tools\sdk\build-tools\34.0.0"
$androidJar = "$tools\sdk\platforms\android-34\android.jar"

Set-Location $PSScriptRoot
$out = "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

function Check($step) { if ($LASTEXITCODE -ne 0) { throw "Falló: $step" } }

& "$bt\aapt2.exe" compile --dir res -o "$out\res.zip"; Check "aapt2 compile"
& "$bt\aapt2.exe" link -I $androidJar --manifest AndroidManifest.xml -A assets -o "$out\unsigned.apk" "$out\res.zip"; Check "aapt2 link"

$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 8 -classpath $androidJar -encoding UTF-8 -d "$out\classes" $sources; Check "javac"

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 21 --lib $androidJar --output "$out\dex" $classes; Check "d8"

Push-Location "$out\dex"
& "$bt\aapt.exe" add ..\unsigned.apk classes.dex | Out-Null; $code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw "Falló: aapt add" }

& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"; Check "zipalign"

# Clave de firma propia; se conserva para poder actualizar la app sin desinstalarla
$ks = "inicio.keystore"
# Contraseña de la clave de firma: variable INICIO_KS_PASS o se pide al compilar (está en Bitwarden)
if (-not $env:INICIO_KS_PASS) {
  $env:INICIO_KS_PASS = [Net.NetworkCredential]::new('', (Read-Host 'Contraseña de inicio.keystore' -AsSecureString)).Password
}
if (-not (Test-Path $ks)) {
  $ErrorActionPreference = 'Continue'   # keytool escribe en stderr aunque vaya bien
  & keytool -genkeypair -keystore $ks -storepass:env INICIO_KS_PASS -keypass:env INICIO_KS_PASS -alias inicio `
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Inicio TV" 2>&1 | Out-Null; Check "keytool"
  $ErrorActionPreference = 'Stop'
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass env:INICIO_KS_PASS --out "$out\Inicio.apk" "$out\aligned.apk"; Check "apksigner"

"OK: $((Resolve-Path "$out\Inicio.apk").Path)  ({0:N0} KB)" -f ((Get-Item "$out\Inicio.apk").Length / 1KB)
