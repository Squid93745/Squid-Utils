# Runs the mod's engine outside Minecraft and the Python lab back to back, so
# the two rankings can be compared directly.
#
# Worth running after any change to the scoring formula: the lab is where tuning
# happens, and that is only useful if the mod agrees with it. This is how the
# missing demand-trend factor in the Java port was caught - cost and profit
# matched exactly while coins-per-hour silently diverged.

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

$gson = (Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson" `
    -Recurse -Filter "gson-*.jar" |
    Where-Object { $_.Name -notlike "*sources*" } |
    Sort-Object Name -Descending | Select-Object -First 1).FullName

$classes = Join-Path $root "mod\build\classes\java\main"
if (-not (Test-Path $classes)) {
    Write-Host "build the mod first: cd mod; .\gradlew.bat build" -ForegroundColor Yellow
    exit 1
}

$out = Join-Path $env:TEMP "shardfuse-parity"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$javac = (Get-ChildItem "C:\Program Files\Java" -Directory |
    ForEach-Object { "$($_.FullName)\bin\javac.exe" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1)

& $javac -cp "$classes;$gson" -d $out (Join-Path $root "mod\tools\HeadlessCheck.java")

Write-Host "`n=========== JAVA (mod engine) ===========" -ForegroundColor Cyan
& java -cp "$classes;$gson;$out" HeadlessCheck

Write-Host "`n=========== PYTHON (lab) ===========" -ForegroundColor Cyan
Push-Location $root
python -m shardfuse --top 10 --no-recursive
Pop-Location
