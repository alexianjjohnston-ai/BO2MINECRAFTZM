# Finds the Black Ops II install and prints its folder (nothing if not found). Read-only except the remembered choice in .bo2dir.
# Order: remembered choice -> Steam (registry + every library in libraryfolders.vdf) -> uninstall registry -> common folders on
# every drive -> shallow scan of each drive -> folder picker. Pass -NoPrompt to skip the picker.
param([switch]$NoPrompt)
$ErrorActionPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$memo = Join-Path $root '.bo2dir'
$marker = 'sound\zmb_common.all.sabl'
function Ok($p) { $p -and (Test-Path -LiteralPath (Join-Path $p $marker)) }
function Done($p) { [Console]::Out.WriteLine($p); exit 0 }

if (Test-Path $memo) { $p = (Get-Content $memo -Raw).Trim(); if (Ok $p) { Done $p } }

$names = 'Call of Duty Black Ops II', 'Call of Duty - Black Ops II', 'Call of Duty Black Ops 2', 'Black Ops II', 'Black Ops 2'
$libs = New-Object System.Collections.Generic.List[string]
foreach ($k in 'HKCU:\Software\Valve\Steam', 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam', 'HKLM:\SOFTWARE\Valve\Steam') {
  $v = (Get-ItemProperty $k).SteamPath; if (-not $v) { $v = (Get-ItemProperty $k).InstallPath }
  if ($v) {
    $v = $v -replace '/', '\'; $libs.Add($v)
    $vdf = Join-Path $v 'steamapps\libraryfolders.vdf'
    if (Test-Path $vdf) { foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) { $libs.Add(($m.Groups[1].Value -replace '\\\\', '\')) } }
  }
}
foreach ($l in $libs) { foreach ($n in $names) { $p = Join-Path $l "steamapps\common\$n"; if (Ok $p) { Done $p } } }

# Installer entries (GOG, retail, other launchers) that record an install folder.
foreach ($k in 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall') {
  foreach ($e in Get-ChildItem $k) {
    $d = Get-ItemProperty $e.PSPath
    if ($d.DisplayName -match 'Black Ops (II|2)' -and $d.InstallLocation -and (Ok $d.InstallLocation)) { Done $d.InstallLocation.TrimEnd('\') }
  }
}

# Common spots on every local drive.
$drives = [IO.DriveInfo]::GetDrives() | Where-Object { $_.DriveType -in 'Fixed', 'Removable' -and $_.IsReady } | ForEach-Object { $_.RootDirectory.FullName }
$bases = 'SteamLibrary\steamapps\common', 'Steam\steamapps\common', 'Program Files (x86)\Steam\steamapps\common', 'Program Files\Steam\steamapps\common',
  'Games\Steam\steamapps\common', 'Games', 'GOG Games', 'Program Files (x86)\GOG Galaxy\Games', 'Program Files\Epic Games', 'Program Files (x86)\Activision', 'Program Files\Activision', 'Activision', ''
foreach ($d in $drives) { foreach ($b in $bases) { foreach ($n in $names) { $p = Join-Path (Join-Path $d $b) $n; if (Ok $p) { Done $p } } } }

# Shallow scan: any folder up to 4 levels deep whose name looks like Black Ops II, and any "steamapps\common" found on the way.
foreach ($d in $drives) {
  $skip = 'Windows', 'ProgramData', '$Recycle.Bin', 'System Volume Information', 'AppData'
  $queue = New-Object System.Collections.Queue; $queue.Enqueue(@($d, 0))
  while ($queue.Count) {
    $item = $queue.Dequeue()
    foreach ($c in Get-ChildItem -LiteralPath $item[0] -Directory -Force) {
      if ($skip -contains $c.Name) { continue }
      if ($c.Name -match 'Black\s*Ops\s*(II|2)' -and (Ok $c.FullName)) { Done $c.FullName }
      if ($item[1] -lt 3) { $queue.Enqueue(@($c.FullName, $item[1] + 1)) }
    }
  }
}

if ($NoPrompt) { exit 0 }

# Not found: let the user pick the folder, and remember it.
Add-Type -AssemblyName System.Windows.Forms
[Console]::Error.WriteLine('[bo2] Could not find Black Ops II automatically. Pick its install folder (the one containing the "sound" folder).')
while ($true) {
  $f = New-Object System.Windows.Forms.FolderBrowserDialog
  $f.Description = 'Select your Call of Duty Black Ops II folder (contains sound\zmb_common.all.sabl). Cancel to skip.'
  $f.ShowNewFolderButton = $false
  if ($f.ShowDialog() -ne 'OK') { exit 0 }
  if (Ok $f.SelectedPath) { Set-Content -LiteralPath $memo -Value $f.SelectedPath -Encoding ASCII; Done $f.SelectedPath }
  [void][System.Windows.Forms.MessageBox]::Show("That folder does not contain sound\zmb_common.all.sabl. Pick the Black Ops II folder itself, or Cancel to skip.", 'Block Ops 2')
}
