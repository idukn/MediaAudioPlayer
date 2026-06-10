<#
.SYNOPSIS
Builds and installs the Media Audio Finder electron app for Windows.
Automatically downloads a portable version of Node.js if it is not installed.
Uses a local temp folder to bypass UNC path issues when running from WSL.
#>

$ErrorActionPreference = 'Stop'

$AppName = "Media Audio Finder"
$ScriptDir = $PSScriptRoot
$OriginalAppDir = Join-Path $ScriptDir "electron"
$NodeVersion = "v20.18.0"
$NodeFolderName = "node-$NodeVersion-win-x64"
$NodeZipName = "$NodeFolderName.zip"
$NodeUrl = "https://nodejs.org/dist/$NodeVersion/$NodeZipName"
$TempDir = $env:TEMP
$NodeExtractedDir = Join-Path $TempDir $NodeFolderName

# Ensure Node.js is available
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host "Node.js is not installed on Windows. Downloading portable Node.js ($NodeVersion)..."
    
    if (-not (Test-Path $NodeExtractedDir)) {
        $NodeZipPath = Join-Path $TempDir $NodeZipName
        if (-not (Test-Path $NodeZipPath)) {
            Write-Host "Downloading $NodeUrl..."
            Invoke-WebRequest -Uri $NodeUrl -OutFile $NodeZipPath
        }
        
        Write-Host "Extracting Node.js..."
        Expand-Archive -Path $NodeZipPath -DestinationPath $TempDir -Force
    }
    
    Write-Host "Setting up Node.js environment..."
    $env:Path = "$NodeExtractedDir;" + $env:Path
    
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Write-Error "Failed to set up portable Node.js."
        exit 1
    }
    Write-Host "Portable Node.js is ready."
} else {
    Write-Host "Node.js is installed on Windows. Using local Node.js..."
}

# Copy to a local temp folder to bypass CMD UNC path limitations
$BuildAppDir = Join-Path $TempDir "MediaAudioFinder_Build"
Write-Host "Copying project to local temp folder ($BuildAppDir) for building..."

if (Test-Path $BuildAppDir) {
    Remove-Item -Recurse -Force $BuildAppDir
}

New-Item -ItemType Directory -Force -Path $BuildAppDir | Out-Null
Get-ChildItem -Path $OriginalAppDir | Where-Object { $_.Name -notin @('node_modules', 'dist') } | Copy-Item -Recurse -Force -Destination $BuildAppDir

Set-Location $BuildAppDir

if (Test-Path "dist") {
    Remove-Item -Recurse -Force "dist"
}
# Remove node_modules just in case it was copied from WSL (might have linux binaries)
if (Test-Path "node_modules") {
    Remove-Item -Recurse -Force "node_modules"
}

Write-Host "Installing dependencies..."
cmd /c "npm install"

if ($LASTEXITCODE -ne 0) {
    Write-Error "npm install failed."
    exit 1
}

Write-Host "Pre-populating winCodeSign cache to bypass symlink errors..."
$WinCodeSignCacheDir = Join-Path $env:LOCALAPPDATA "electron-builder\Cache\winCodeSign"
$WinCodeSignTargetDir = Join-Path $WinCodeSignCacheDir "winCodeSign-2.6.0"

if (-not (Test-Path $WinCodeSignTargetDir)) {
    New-Item -ItemType Directory -Force -Path $WinCodeSignTargetDir | Out-Null
    $WinCodeSignZipPath = Join-Path $TempDir "winCodeSign-2.6.0.7z"
    if (-not (Test-Path $WinCodeSignZipPath)) {
        Write-Host "Downloading winCodeSign..."
        Invoke-WebRequest -Uri "https://github.com/electron-userland/electron-builder-binaries/releases/download/winCodeSign-2.6.0/winCodeSign-2.6.0.7z" -OutFile $WinCodeSignZipPath
    }
    
    $7zExe = Join-Path $BuildAppDir "node_modules\7zip-bin\win\x64\7za.exe"
    Write-Host "Extracting winCodeSign (ignoring symlink errors)..."
    # Extract, ignoring errors (-snld doesn't work to ignore, we just let it fail and keep what it extracted)
    & $7zExe x $WinCodeSignZipPath -o"$WinCodeSignTargetDir" -y 2>&1 | Out-Null
    
    # We ignore the exit code of 7za because it WILL fail on the symlinks, but the windows binaries we need will have been extracted successfully.
    Write-Host "winCodeSign cache populated."
} else {
    Write-Host "winCodeSign cache already exists."
}

Write-Host "Disabling mac target in package.json to avoid winCodeSign download..."
$ModifyPackageJson = @"
const fs = require('fs');
const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'));
if (pkg.build && pkg.build.mac) {
    delete pkg.build.mac;
    fs.writeFileSync('package.json', JSON.stringify(pkg, null, 2));
}
"@
Set-Content -Path "disable-mac.js" -Value $ModifyPackageJson
node disable-mac.js

Write-Host "Building Windows app (unpacked)..."
$env:CSC_IDENTITY_AUTO_DISCOVERY="false"
$env:WIN_CSC_LINK=""
# Force electron-builder to avoid downloading winCodeSign which contains symlinks failing on normal Windows users
$env:ELECTRON_BUILDER_ALLOW_UNRESOLVED_DEPENDENCIES="true"
cmd /c "npx electron-builder --win --dir -c.mac.identity=null"

if ($LASTEXITCODE -ne 0) {
    Write-Error "electron-builder failed."
    exit 1
}

$BuiltAppPath = Join-Path $BuildAppDir "dist\win-unpacked"

if (-not (Test-Path $BuiltAppPath)) {
    Write-Error "Build failed: unpacked app not found at $BuiltAppPath"
    exit 1
}

# Install to LocalAppData\Programs
$InstallDir = Join-Path $env:LOCALAPPDATA "Programs\$AppName"
$ExeFileName = "$AppName.exe"

Write-Host "Installing app to $InstallDir..."

function Stop-InstalledApp {
    param([string]$TargetDir)

    & taskkill /F /FI "IMAGENAME eq $ExeFileName" /T 2>$null | Out-Null

    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ExecutablePath -and
            $_.ExecutablePath.StartsWith($TargetDir, [StringComparison]::OrdinalIgnoreCase)
        } |
        ForEach-Object {
            Write-Host "Stopping PID $($_.ProcessId): $($_.Name)"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }

    Start-Sleep -Seconds 3
}

function Sync-InstallDir {
    param(
        [string]$SourceDir,
        [string]$TargetDir
    )

    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

    Write-Host "Syncing app files in place..."
    & robocopy $SourceDir $TargetDir /MIR /R:2 /W:2 /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null
    $RobocopyExit = $LASTEXITCODE

    if ($RobocopyExit -ge 8) {
        Write-Error "Failed to sync app files (robocopy exit code $RobocopyExit)."
        exit 1
    }

    if ($RobocopyExit -ge 2) {
        Write-Host "Some files are still in use. Close '$AppName' and run this script again to finish updating."
    }
}

function Install-AppToTarget {
    param(
        [string]$SourceDir,
        [string]$TargetDir
    )

    Stop-InstalledApp -TargetDir $TargetDir

    if (-not (Test-Path $TargetDir)) {
        New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
        Copy-Item -Recurse -Force "$SourceDir\*" $TargetDir
        return
    }

    for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
        try {
            Remove-Item -Recurse -Force $TargetDir -ErrorAction Stop
            New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
            Copy-Item -Recurse -Force "$SourceDir\*" $TargetDir
            return
        } catch {
            if ($Attempt -lt 3) {
                Write-Host "Install directory is locked, retrying... ($Attempt/3)"
                Stop-InstalledApp -TargetDir $TargetDir
            }
        }
    }

    $BackupName = "$(Split-Path $TargetDir -Leaf).old_$(Get-Date -Format 'yyyyMMddHHmmss')"
    Write-Host "Install directory is locked; renaming old install to $BackupName..."
    try {
        Rename-Item -Path $TargetDir -NewName $BackupName -ErrorAction Stop
        New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
        Copy-Item -Recurse -Force "$SourceDir\*" $TargetDir

        $BackupDir = Join-Path (Split-Path $TargetDir -Parent) $BackupName
        Remove-Item -Recurse -Force $BackupDir -ErrorAction SilentlyContinue
        return
    } catch {
        Write-Host "Rename failed; falling back to in-place sync..."
        Sync-InstallDir -SourceDir $SourceDir -TargetDir $TargetDir
    }
}

Install-AppToTarget -SourceDir $BuiltAppPath -TargetDir $InstallDir

Write-Host "Creating Start Menu shortcut..."
$WshShell = New-Object -comObject WScript.Shell
$StartMenuPath = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$AppName.lnk"
$Shortcut = $WshShell.CreateShortcut($StartMenuPath)
$Shortcut.TargetPath = Join-Path $InstallDir "$AppName.exe"
$Shortcut.WorkingDirectory = $InstallDir
$Shortcut.Save()

Write-Host "Cleaning up temp build folder..."
Set-Location $ScriptDir
Remove-Item -Recurse -Force $BuildAppDir -ErrorAction SilentlyContinue

Write-Host "Installed successfully: $InstallDir"
Write-Host "You can now launch '$AppName' from the Start Menu."