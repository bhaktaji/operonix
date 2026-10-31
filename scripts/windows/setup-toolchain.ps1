<#
.SYNOPSIS
    Installs the Operonix build toolchain in a machine-wide location and registers it in the
    persistent environment.

.DESCRIPTION
    Moves the JDK and Maven that were previously vendored under <repo>\.tools to C:\Tools and points
    JAVA_HOME / MAVEN_HOME / PATH at them.

    Two scopes are supported:

      -Scope User     Writes JAVA_HOME, MAVEN_HOME and the two bin directories to the *user*
                      environment. No administrator rights needed, but a machine-level JDK that
                      appears earlier in PATH still wins for a bare `java` call.
      -Scope Machine  Prepends the JDK bin directory to the *machine* PATH and sets the machine
                      JAVA_HOME, so `java`, `javac` and `mvn` all resolve to the same JDK 21 for
                      every account. Requires an elevated PowerShell.

    The PATH is read from the registry, edited entry by entry and written back as a plain string.
    `setx` is deliberately not used: it truncates at 1024 characters and destroys REG_EXPAND_SZ
    variables such as %JAVA_HOME%\bin.

.PARAMETER SourceRoot
    Directory containing the vendored `jdk-*` / `apache-maven-*` folders. Defaults to the repo's
    .tools directory.

.PARAMETER TargetRoot
    Install root. Defaults to C:\Tools.

.PARAMETER Scope
    User or Machine. Defaults to User.

.EXAMPLE
    # 1) As a normal user: move the files and set JAVA_HOME / MAVEN_HOME / user PATH.
    powershell -ExecutionPolicy Bypass -File scripts\windows\setup-toolchain.ps1

.EXAMPLE
    # 2) From an elevated PowerShell, once: make JDK 21 the default java for the whole machine.
    powershell -ExecutionPolicy Bypass -File scripts\windows\setup-toolchain.ps1 -Scope Machine
#>
[CmdletBinding()]
param(
    [string] $SourceRoot,
    [string] $TargetRoot = 'C:\Tools',
    [ValidateSet('User', 'Machine')]
    [string] $Scope = 'User'
)

$ErrorActionPreference = 'Stop'

if (-not $SourceRoot) {
    # $PSScriptRoot is not reliably populated while parameter defaults are evaluated.
    $scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
    $SourceRoot = Join-Path $scriptDirectory '..\..\.tools'
}

function Write-Step($message) { Write-Host "==> $message" }

function Move-IfNeeded {
    param([string] $Name)

    $source = Join-Path $SourceRoot $Name
    $target = Join-Path $TargetRoot $Name

    if (Test-Path -LiteralPath $target) {
        Write-Step "$target already exists, leaving it alone"
        return
    }
    if (-not (Test-Path -LiteralPath $source)) {
        Write-Step "$source not present, nothing to move"
        return
    }

    Write-Step "moving $source -> $target"
    Move-Item -LiteralPath $source -Destination $target
}

<#
    Adds entries to a persistent PATH without dropping anything that is already there.
    Reads the raw registry value so unexpanded variables survive, removes duplicates and
    trailing separators, then writes the joined string back with ExpandString semantics.
#>
function Add-PathEntries {
    param([string] $ScopeName, [string[]] $Entries)

    $current = [Environment]::GetEnvironmentVariable('Path', $ScopeName)
    if ($null -eq $current) { $current = '' }

    $existing = @($current -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    $normalized = @($existing | ForEach-Object { $_.TrimEnd('\') })
    $added = @()

    foreach ($entry in $Entries) {
        if ($normalized -notcontains $entry.TrimEnd('\')) {
            $added += $entry
        }
    }

    if ($added.Count -eq 0) {
        Write-Step "PATH ($ScopeName) already contains the toolchain"
        return
    }

    $updated = (@($added) + $existing) -join ';'
    [Environment]::SetEnvironmentVariable('Path', $updated, $ScopeName)
    Write-Step "PATH ($ScopeName) prepended with: $($added -join '; ')"
}

Write-Step "source: $SourceRoot"
Write-Step "target: $TargetRoot (scope: $Scope)"

if (-not (Test-Path -LiteralPath $TargetRoot)) {
    New-Item -ItemType Directory -Path $TargetRoot | Out-Null
    Write-Step "created $TargetRoot"
}

$jdkName = $null
if (Test-Path -LiteralPath $SourceRoot) {
    $jdkName = Get-ChildItem -LiteralPath $SourceRoot -Directory -Filter 'jdk-*' |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $jdkName) {
    $jdkName = (Get-ChildItem -LiteralPath $TargetRoot -Directory -Filter 'jdk-*' |
        Select-Object -First 1 -ExpandProperty Name)
}
if (-not $jdkName) {
    throw "No jdk-* directory found in $SourceRoot or $TargetRoot. Install a JDK 21 first."
}

$mavenName = $null
if (Test-Path -LiteralPath $SourceRoot) {
    $mavenName = Get-ChildItem -LiteralPath $SourceRoot -Directory -Filter 'apache-maven-*' |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $mavenName) {
    $mavenName = (Get-ChildItem -LiteralPath $TargetRoot -Directory -Filter 'apache-maven-*' |
        Select-Object -First 1 -ExpandProperty Name)
}
if (-not $mavenName) {
    throw "No apache-maven-* directory found in $SourceRoot or $TargetRoot. Install Maven first."
}

if ($Scope -eq 'User') {
    Move-IfNeeded $jdkName
    Move-IfNeeded $mavenName
}

$jdkHome = Join-Path $TargetRoot $jdkName
$mavenHome = Join-Path $TargetRoot $mavenName

# java -version writes to stderr; merging through cmd keeps ErrorActionPreference=Stop from
# treating a successful version probe as a terminating error.
$javaExe = Join-Path $jdkHome 'bin\java.exe'
$jdkVersion = (cmd /c "`"$javaExe`" -version 2>&1" | Select-Object -First 1)
Write-Step "JDK at $jdkHome -> $jdkVersion"

[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkHome, $Scope)
[Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenHome, $Scope)
Write-Step "JAVA_HOME ($Scope) = $jdkHome"
Write-Step "MAVEN_HOME ($Scope) = $mavenHome"

Add-PathEntries $Scope @('%JAVA_HOME%\bin', '%MAVEN_HOME%\bin')

Write-Step 'done. Open a NEW terminal for the variables to take effect.'
if ($Scope -eq 'User') {
    Write-Host ''
    Write-Host 'Note: if an older JDK is registered in the machine PATH, a bare `java` still resolves'
    Write-Host 'to it. Run this script once from an elevated PowerShell with -Scope Machine to make'
    Write-Host 'JDK 21 the default for every account.'
}
