# Run by play.bat on every start. Keeps the game current with GitHub and fetches the converter tool the game needs.
# Never stops the launch: every failure just prints a note and play.bat carries on.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = 'alexianjjohnston-ai/BO2MINECRAFTZM'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Sync-Git {
    Push-Location $root
    try {
        git pull --ff-only
        if ($LASTEXITCODE -ne 0) { Write-Host '[sync] Could not fast-forward (local changes in the way or no upstream). Continuing with the current version.' }
        else { Write-Host "[sync] Version $(git rev-parse --short HEAD)" }
    } finally { Pop-Location }
}

# Downloaded the project as a zip (no .git folder): fetch the zip again and copy it over, leaving the player's saves/cache alone.
function Sync-Zip {
    $tmp = Join-Path $env:TEMP ('zc-update-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $zip = Join-Path $tmp 'main.zip'
        Invoke-WebRequest "https://github.com/$repo/archive/refs/heads/main.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $tmp -Force
        $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
        $stamp = Join-Path $root '.zc-version'
        $new = (Get-FileHash $zip).Hash
        if ((Test-Path $stamp) -and ((Get-Content $stamp -Raw).Trim() -eq $new)) { Write-Host '[sync] Already up to date.'; return }
        # play.bat is running right now, so a new copy goes beside it as play.bat.new instead of over it.
        robocopy $src.FullName $root /E /NFL /NDL /NJH /NJS /NP /XD run .gradle build .git /XF play.bat | Out-Null
        if ((Get-FileHash (Join-Path $src.FullName 'play.bat')).Hash -ne (Get-FileHash (Join-Path $root 'play.bat')).Hash) {
            Copy-Item (Join-Path $src.FullName 'play.bat') (Join-Path $root 'play.bat.new') -Force
            Write-Host '[sync] A newer play.bat was saved as play.bat.new - replace play.bat with it when convenient.'
        }
        Set-Content $stamp $new
        Write-Host '[sync] Updated from GitHub.'
    } finally { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}

try {
    if ((Test-Path (Join-Path $root '.git')) -and (Get-Command git -ErrorAction SilentlyContinue)) { Sync-Git } else { Sync-Zip }
} catch {
    Write-Host "[sync] Skipped ($($_.Exception.Message)). Continuing with the current version."
    if ($_.Exception.Message -match '404|401|403') { Write-Host "[sync] The repository is private, so a plain zip can't update itself. Install Git, then run: git clone https://github.com/$repo  (sign in once) and play from that folder." }
}

# OpenAssetTools Unlinker (separate GPL-3.0 program from its official release). The game uses it to turn the models and menu art in YOUR
# Black Ops II install into Minecraft-readable files on your PC. Without it the game falls back to plain shapes.
try {
    $oat = Join-Path $root 'mod\run\zombiecraft\tools\oat'
    if ($env:ZOMBIECRAFT_OAT_DIR -and (Test-Path (Join-Path $env:ZOMBIECRAFT_OAT_DIR 'Unlinker.exe'))) { Write-Host "[oat] Using $env:ZOMBIECRAFT_OAT_DIR" }
    elseif (Test-Path (Join-Path $oat 'Unlinker.exe')) { Write-Host '[oat] OpenAssetTools ready.' }
    else {
        Write-Host '[oat] Downloading OpenAssetTools v0.33.0 (about 4 MB)...'
        New-Item -ItemType Directory -Force -Path $oat | Out-Null
        $zip = Join-Path $env:TEMP 'oat-windows.zip'
        Invoke-WebRequest "https://github.com/Laupetin/OpenAssetTools/releases/download/v0.33.0/oat-windows.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $oat -Force
        Remove-Item $zip -Force
        Write-Host '[oat] Installed.'
    }
} catch { Write-Host "[oat] Could not get OpenAssetTools ($($_.Exception.Message)). Models and menu art will use plain shapes." }

# Java 25 runs the build tool only. Installed beside the game (no admin rights) when no JDK 25 is on the PC.
try {
    $have = @("$env:JAVA_HOME") + (Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft', (Join-Path $root '.jdk') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $ok = $have | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) -and ((Split-Path $_ -Leaf) -like 'jdk-25*' -or (Select-String -Path (Join-Path $_ 'release') -Pattern 'JAVA_VERSION="25' -Quiet -ErrorAction SilentlyContinue)) }
    if ($ok) { Write-Host '[java] JDK 25 ready.' }
    else {
        Write-Host '[java] Downloading JDK 25 (about 200 MB)...'
        $jdk = Join-Path $root '.jdk'
        New-Item -ItemType Directory -Force -Path $jdk | Out-Null
        $zip = Join-Path $env:TEMP 'jdk25.zip'
        Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $jdk -Force
        Remove-Item $zip -Force
        Write-Host '[java] Installed.'
    }
} catch { Write-Host "[java] Could not install JDK 25 ($($_.Exception.Message))." }
