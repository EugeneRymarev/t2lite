<#
.SYNOPSIS
    Сборка APK t2 Lite с автоматическим увеличением версии.

.DESCRIPTION
    Запуск без параметров (например, двойной щелчок по build.bat) показывает меню с выбором действия.
    По умолчанию (Enter) — собрать release с новой версией и установить на телефон, подключённый по USB.

    Версия хранится в version.properties (VERSION_NAME = x.y.z, VERSION_CODE = целое число).
    При каждой сборке скрипт увеличивает версию, запускает Gradle и кладёт готовый APK в папку dist\
    с версией в имени, например dist\t2lite-1.0.4-build5-release.apk.
    Если сборка упала, version.properties возвращается к прежнему значению.
    В историю версий (app\src\main\assets\changelog.md) скрипт сам ставит номер версии и дату
    над пунктами из раздела «## Готовится» — их показывает экран «История версий» в приложении.

    Release подписывается своим ключом keystore\t2lite-release.jks. При первой release-сборке скрипт
    сам создаёт ключ и keystore.properties со случайным паролем. Эти два файла нужно сохранить:
    без них обновить уже установленное приложение не получится (только удалить и поставить заново).

.PARAMETER Bump
    Что увеличить: patch (по умолчанию, 1.0.3 -> 1.0.4), minor (1.0.3 -> 1.1.0), major (1.0.3 -> 2.0.0),
    code (только VERSION_CODE, имя версии не меняется), none (ничего не менять, пересобрать ту же версию).
    VERSION_CODE увеличивается на 1 при любом значении, кроме none.

.PARAMETER Type
    release (по умолчанию), debug или both.

.PARAMETER Install
    После сборки установить APK через adb. Куда ставить, задаёт -Target (по умолчанию телефон по USB).

.PARAMETER Target
    usb (по умолчанию): телефон, подключённый проводом. phone: любой физический телефон (USB или Wi-Fi).
    emulator: эмулятор. all: все подключённые устройства.
    auto: если устройство одно, ставит на него; если несколько, то на телефон.

.PARAMETER NoBuild
    Не собирать, а установить последний APK из dist\ (подразумевает -Install).

.PARAMETER Menu
    Показать меню, даже если заданы другие параметры.

.PARAMETER Serial
    Серийный номер конкретного устройства из "adb devices" (вместо -Target).

.PARAMETER Adb
    Путь к adb.exe, если скрипт не нашёл его сам (ищет в PATH, C:\soft\adb и Android SDK).

.PARAMETER Clean
    Перед сборкой выполнить gradle clean.

.EXAMPLE
    .\build.ps1                         # меню
    .\build.ps1 -Install                # release, +1 к версии, установка на телефон по USB
    .\build.ps1 -NoBuild                # поставить последний собранный release
    .\build.ps1 -Bump minor
    .\build.ps1 -Type debug -Bump none -Install
    .\build.ps1 -Install -Target phone
    .\build.ps1 -Bump none -Install -Serial R58M123ABC
#>
param(
    [ValidateSet('patch', 'minor', 'major', 'code', 'none')]
    [string]$Bump = 'patch',

    [ValidateSet('release', 'debug', 'both')]
    [string]$Type = 'release',

    [switch]$Install,

    [ValidateSet('usb', 'phone', 'emulator', 'all', 'auto')]
    [string]$Target = 'usb',

    [string]$Serial,
    [string]$Adb,
    [switch]$NoBuild,
    [switch]$Menu,
    [switch]$Clean
)
$ShowMenu = $Menu -or ($PSBoundParameters.Count -eq 0)

# Из меню (двойной щелчок) окно не закрывается сразу, чтобы был виден результат или ошибка.
function Finish([int]$exitCode) {
    if ($ShowMenu) { [void](Read-Host "`nНажмите Enter, чтобы закрыть окно") }
    exit $exitCode
}
trap {
    Write-Host "`nОшибка: $($_.Exception.Message)" -ForegroundColor Red
    Finish 1
}
# -Target, -Serial или -NoBuild без -Install тоже означают установку.
if ($Serial -or $NoBuild -or $PSBoundParameters.ContainsKey('Target')) { $Install = [switch]$true }

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location $Root

$VersionFile = Join-Path $Root 'version.properties'
$ChangelogFile = Join-Path $Root 'app\src\main\assets\changelog.md'
$KeystorePropsFile = Join-Path $Root 'keystore.properties'
$KeystoreFile = Join-Path $Root 'keystore\t2lite-release.jks'
$DistDir = Join-Path $Root 'dist'

function Write-Step([string]$text) { Write-Host "`n==> $text" -ForegroundColor Cyan }

# Читает .properties в упорядоченный словарь (без разбора многострочных значений — здесь они не нужны).
function Read-Props([string]$path) {
    $map = [ordered]@{}
    if (Test-Path $path) {
        foreach ($line in Get-Content -LiteralPath $path) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
            $i = $t.IndexOf('=')
            if ($i -lt 1) { continue }
            $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
        }
    }
    return $map
}

# Снимает экранирование .properties: "C\:\\Users\\name" -> "C:\Users\name".
function Unescape-Prop([string]$value) { return ($value -replace '\\(.)', '$1') }

# Запускает внешнюю программу, выводит её вывод в консоль и возвращает код выхода.
function Invoke-Tool([string]$exe, [string[]]$argv) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $exe @argv | Out-Host } finally { $ErrorActionPreference = $old }
    return $LASTEXITCODE
}

# JDK: тот же, что выбран в Android Studio (.gradle\config.properties), затем JAVA_HOME,
# встроенный в Android Studio jbr и скачанные студией JDK в %USERPROFILE%\.jdks.
function Find-JavaHome {
    $candidates = @()
    $cfg = Read-Props (Join-Path $Root '.gradle\config.properties')
    if ($cfg['java.home']) { $candidates += (Unescape-Prop $cfg['java.home']) }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr') }
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdks) {
        $candidates += (Get-ChildItem -LiteralPath $jdks -Directory | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    }
    foreach ($p in $candidates) {
        if ($p -and (Test-Path (Join-Path $p 'bin\java.exe'))) { return $p }
    }
    return $null
}

function Find-Adb {
    $candidates = @()
    if ($Adb) { $candidates += $Adb; $candidates += (Join-Path $Adb 'adb.exe') }
    if ($env:ADB) { $candidates += $env:ADB }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $candidates += $cmd.Source }
    $candidates += 'C:\soft\adb\adb.exe'
    $candidates += 'C:\soft\adb\platform-tools\adb.exe'
    $sdks = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $lp = Read-Props (Join-Path $Root 'local.properties')
    if ($lp['sdk.dir']) { $sdks += (Unescape-Prop $lp['sdk.dir']) }
    if ($env:LOCALAPPDATA) { $sdks += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    foreach ($sdk in $sdks) { if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') } }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c -PathType Leaf)) { return $c }
    }
    return $null
}

# Запускает adb и возвращает вывод одной строкой (stderr тоже), код выхода — в $LASTEXITCODE.
function Invoke-Adb([string]$adbExe, [string[]]$argv) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& $adbExe @argv 2>&1 | ForEach-Object { "$_" } | Out-String) } finally { $ErrorActionPreference = $old }
}

# Список устройств из "adb devices -l": Serial, State (device / unauthorized / offline), IsEmulator, Name.
function Get-AdbDevices([string]$adbExe) {
    $text = Invoke-Adb $adbExe @('devices', '-l')
    $list = @()
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline|no permissions|recovery|sideload|bootloader)\b(.*)$') {
            $serial = $Matches[1]; $state = $Matches[2]; $rest = $Matches[3]
            $name = if ($rest -match 'model:(\S+)') { $Matches[1] } else { $serial }
            $isEmu = ($serial -like 'emulator-*') -or ($rest -match 'product:sdk_|model:sdk_|device:emu|device:generic')
            # Wi-Fi: "192.168.1.5:5555" или "adb-XXXX._adb-tls-connect._tcp"; всё остальное (кроме эмулятора) — провод.
            $isNet = ($serial -match ':\d+$') -or ($serial -match '_adb-tls-connect|\._tcp')
            $list += [pscustomobject]@{ Serial = $serial; State = $state; IsEmulator = $isEmu; IsUsb = (-not $isEmu -and -not $isNet); Name = $name }
        }
    }
    return , $list
}

function Show-Devices($devices) {
    if (@($devices).Count -eq 0) { Write-Host '    (нет устройств)'; return }
    foreach ($d in $devices) {
        $kind = if ($d.IsEmulator) { 'эмулятор' } elseif ($d.IsUsb) { 'USB' } else { 'Wi-Fi' }
        Write-Host ("    {0,-24} {1,-10} {2} ({3})" -f $d.Serial, $kind, $d.Name, $d.State)
    }
}

function Select-InstallTargets([string]$adbExe) {
    $all = Get-AdbDevices $adbExe
    if ($all.Count -eq 0) {
        throw ("adb не видит ни одного устройства. Для телефона: включите «Для разработчиков» → «Отладка по USB», " +
            "подключите кабель (режим передачи файлов) и разрешите отладку в окне на телефоне.")
    }
    Write-Host '  Устройства adb:'
    Show-Devices $all

    if ($Serial) {
        $picked = @($all | Where-Object { $_.Serial -eq $Serial })
        if ($picked.Count -eq 0) { throw "Устройство $Serial не найдено в adb devices." }
    }
    else {
        $phones = @($all | Where-Object { -not $_.IsEmulator })
        $usb = @($all | Where-Object { $_.IsUsb })
        $emus = @($all | Where-Object { $_.IsEmulator })
        switch ($Target) {
            'usb' { $picked = $usb }
            'phone' { $picked = $phones }
            'emulator' { $picked = $emus }
            'all' { $picked = $all }
            default {
                if ($all.Count -eq 1) { $picked = $all }
                elseif ($phones.Count -ge 1) { $picked = @($phones[0]) }
                else { $picked = @($all[0]) }
                if ($phones.Count -gt 1) {
                    Write-Host "  Подключено несколько телефонов, ставлю на $($phones[0].Serial). Другой можно выбрать через -Serial." -ForegroundColor Yellow
                }
            }
        }
        if ($Target -in @('usb', 'phone') -and $picked.Count -gt 1) {
            Write-Host "  Подключено несколько телефонов, ставлю на $($picked[0].Serial). Другой можно выбрать через -Serial." -ForegroundColor Yellow
            $picked = @($picked[0])
        }
        if ($picked.Count -eq 0) {
            $what = switch ($Target) {
                'emulator' { 'эмулятор (запустите его в Android Studio)' }
                'usb' { 'телефон, подключённый по USB. Проверьте кабель (режим передачи файлов) и «Отладку по USB» на телефоне' }
                default { 'физический телефон' }
            }
            throw "Не найден $what."
        }
    }

    foreach ($d in $picked) {
        if ($d.State -eq 'unauthorized') {
            throw "Телефон $($d.Name) не разрешил отладку. Разблокируйте его и нажмите «Разрешить» в окне «Отладка по USB», затем запустите скрипт ещё раз (можно с -Bump none)."
        }
        if ($d.State -ne 'device') {
            throw "Устройство $($d.Serial) в состоянии '$($d.State)'. Переподключите кабель или выполните `"$adbExe`" kill-server."
        }
    }
    return , $picked
}

function Write-Version([string]$name, [int]$code) {
    $lines = @(
        '# t2 Lite app version. build.ps1 bumps it on every build; edit by hand if needed.',
        '# VERSION_NAME is shown to users (major.minor.patch), VERSION_CODE must grow with every APK you install.',
        "VERSION_NAME=$name",
        "VERSION_CODE=$code"
    )
    [IO.File]::WriteAllText($VersionFile, (($lines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)
}

# История версий: app\src\main\assets\changelog.md (её показывает экран «История версий» в приложении).
# Что поменялось, пишется строками «- ...» под заголовком «## Готовится». При сборке с новой версией
# этот заголовок превращается в «## 1.2.3 · build 45 · 22.11.2026», а сверху появляется новый пустой «## Готовится».
# Если под «Готовится» ничего не записано, версия всё равно попадает в историю с пунктом «Мелкие исправления».
# Возвращает прежний текст файла (чтобы откатить, если сборка упала) или $null, если файла нет.
function Update-Changelog([string]$name, [int]$code) {
    if (-not (Test-Path -LiteralPath $ChangelogFile)) { return $null }
    $utf8 = New-Object Text.UTF8Encoding $false
    $oldText = [IO.File]::ReadAllText($ChangelogFile, $utf8)
    $lines = New-Object 'Collections.Generic.List[string]'
    foreach ($l in ($oldText -split "`r?`n")) { $lines.Add($l) }
    $header = "## $name · build $code · " + (Get-Date).ToString('dd.MM.yyyy')
    $pendingTitle = '## Готовится'

    $pending = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].Trim() -eq $pendingTitle) { $pending = $i; break } }
    if ($pending -ge 0) {
        $hasItems = $false
        for ($j = $pending + 1; $j -lt $lines.Count -and -not $lines[$j].StartsWith('## '); $j++) {
            if ($lines[$j].Trim() -ne '' -and -not $lines[$j].Trim().StartsWith('#')) { $hasItems = $true }
        }
        $lines[$pending] = $header
        if (-not $hasItems) { $lines.Insert($pending + 1, '- Мелкие исправления') }
        $lines.InsertRange($pending, [string[]]@($pendingTitle, ''))
    }
    else {
        $first = $lines.Count
        for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith('## ')) { $first = $i; break } }
        $lines.InsertRange($first, [string[]]@($pendingTitle, '', $header, '- Мелкие исправления', ''))
    }
    [IO.File]::WriteAllText($ChangelogFile, ($lines -join "`r`n"), $utf8)
    Write-Host "  История версий: $header"
    return $oldText
}

function New-ReleaseKeystore([string]$javaHome) {
    if (Test-Path $KeystoreFile) {
        throw "Найден $KeystoreFile, но нет keystore.properties. Восстановите keystore.properties из резервной копии (пароль от ключа там)."
    }
    $keytool = Join-Path $javaHome 'bin\keytool.exe'
    New-Item -ItemType Directory -Force -Path (Split-Path $KeystoreFile) | Out-Null

    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pass = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

    Write-Step 'Создаю ключ подписи release (один раз): keystore\t2lite-release.jks'
    # Пароль передаётся keytool через переменную окружения (-storepass:env), а не в командной строке:
    # командную строку запущенной программы видят другие программы (диспетчер задач, журналы Windows).
    # Сам пароль на экран не выводится — он есть только в keystore.properties.
    $env:T2LITE_KEYSTORE_PASS = $pass
    try {
        $code = Invoke-Tool $keytool @(
            '-genkeypair', '-noprompt',
            '-keystore', $KeystoreFile, '-storetype', 'PKCS12',
            '-alias', 't2lite', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-storepass:env', 'T2LITE_KEYSTORE_PASS', '-keypass:env', 'T2LITE_KEYSTORE_PASS',
            '-dname', 'CN=t2 Lite, C=RU'
        )
    }
    finally {
        Remove-Item Env:\T2LITE_KEYSTORE_PASS -ErrorAction SilentlyContinue
    }
    if ($code -ne 0) { throw "keytool завершился с кодом $code" }

    $lines = @(
        '# Release signing key for t2 Lite. Created by build.ps1. KEEP A BACKUP of this file and keystore\t2lite-release.jks:',
        '# without them you cannot update an installed release build.',
        'storeFile=keystore/t2lite-release.jks',
        "storePassword=$pass",
        'keyAlias=t2lite',
        "keyPassword=$pass"
    )
    [IO.File]::WriteAllText($KeystorePropsFile, (($lines -join "`r`n") + "`r`n"), [Text.Encoding]::ASCII)
    Write-Host 'Ключ создан. Сохраните копию папки keystore\ и файла keystore.properties в надёжном месте.' -ForegroundColor Yellow
}

# ---------- меню ----------
if ($ShowMenu) {
    $cur = Read-Props $VersionFile
    $curName = if ($cur['VERSION_NAME']) { $cur['VERSION_NAME'] } else { '1.0.0' }
    $curCode = if ($cur['VERSION_CODE']) { $cur['VERSION_CODE'] } else { '1' }
    $done = $false
    while (-not $done) {
        Write-Host ''
        Write-Host "t2 Lite: сборка APK. Сейчас версия $curName (build $curCode)" -ForegroundColor Cyan
        Write-Host '  1) Собрать release (+1 к версии) и установить на телефон по USB   [Enter]'
        Write-Host '  2) Собрать release (+1 к версии) без установки'
        Write-Host '  3) Установить последний собранный release на телефон по USB, без сборки'
        Write-Host '  4) Собрать debug (версия не меняется) и установить на телефон по USB'
        Write-Host '  5) Собрать release (+1 к версии) и установить на эмулятор'
        Write-Host '  6) Собрать release с новой minor-версией (x.Y.0) и установить на телефон по USB'
        Write-Host '  7) Показать устройства adb'
        Write-Host '  0) Выход'
        $choice = ([string](Read-Host 'Выбор')).Trim()
        if ($choice -eq '') { $choice = '1' }
        $done = $true
        switch ($choice) {
            '1' { $Bump = 'patch'; $Type = 'release'; $Install = $true; $Target = 'usb' }
            '2' { $Bump = 'patch'; $Type = 'release'; $Install = $false }
            '3' { $NoBuild = $true; $Type = 'release'; $Install = $true; $Target = 'usb' }
            '4' { $Bump = 'none'; $Type = 'debug'; $Install = $true; $Target = 'usb' }
            '5' { $Bump = 'patch'; $Type = 'release'; $Install = $true; $Target = 'emulator' }
            '6' { $Bump = 'minor'; $Type = 'release'; $Install = $true; $Target = 'usb' }
            '7' {
                $done = $false
                $a = Find-Adb
                if ($a) { Write-Host "  adb: $a"; Show-Devices (Get-AdbDevices $a) }
                else { Write-Host '  adb не найден (ищу в PATH, C:\soft\adb, Android SDK).' -ForegroundColor Yellow }
            }
            '0' { exit 0 }
            default { $done = $false; Write-Host '  Нет такого пункта.' -ForegroundColor Yellow }
        }
    }
}

$built = @()
if (-not $NoBuild) {
# ---------- версия ----------
if (-not (Test-Path $VersionFile)) { Write-Version '1.0.0' 1 }
$originalVersionText = [IO.File]::ReadAllText($VersionFile)
$v = Read-Props $VersionFile
$oldName = [string]$v['VERSION_NAME']
$oldCode = 0
if (-not [int]::TryParse([string]$v['VERSION_CODE'], [ref]$oldCode)) { throw "В version.properties неверный VERSION_CODE: '$($v['VERSION_CODE'])'" }
if ($oldName -notmatch '^(\d+)\.(\d+)\.(\d+)$') { throw "В version.properties VERSION_NAME должен быть вида 1.2.3, сейчас: '$oldName'" }
$major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
switch ($Bump) {
    'major' { $major++; $minor = 0; $patch = 0 }
    'minor' { $minor++; $patch = 0 }
    'patch' { $patch++ }
}
$newName = "$major.$minor.$patch"
$newCode = if ($Bump -eq 'none') { $oldCode } else { $oldCode + 1 }

$types = if ($Type -eq 'both') { @('debug', 'release') } else { @($Type) }

# ---------- окружение ----------
$javaHome = Find-JavaHome
if (-not $javaHome) {
    throw 'Не найден JDK. Установите Android Studio или задайте переменную JAVA_HOME (нужен JDK 17 или новее).'
}
$env:JAVA_HOME = $javaHome
Write-Host "JDK: $javaHome"

if ($types -contains 'release' -and -not (Test-Path $KeystorePropsFile)) { New-ReleaseKeystore $javaHome }

# ---------- сборка ----------
Write-Step "Версия: $oldName ($oldCode) -> $newName ($newCode); сборка: $($types -join ', ')"
Write-Version $newName $newCode
$originalChangelogText = if ($Bump -ne 'none') { Update-Changelog $newName $newCode } else { $null }

$tasks = @()
if ($Clean) { $tasks += 'clean' }
foreach ($t in $types) { $tasks += ':app:assemble' + $t.Substring(0, 1).ToUpper() + $t.Substring(1) }

$built = @()
$ok = $false
try {
    Write-Step ("gradlew " + ($tasks -join ' '))
    $code = Invoke-Tool (Join-Path $Root 'gradlew.bat') ($tasks + @('--console=plain'))
    if ($code -ne 0) { throw "Gradle завершился с кодом $code" }

    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    foreach ($t in $types) {
        $apk = Get-ChildItem -Path (Join-Path $Root "app\build\outputs\apk\$t") -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $apk) { throw "Не найден APK в app\build\outputs\apk\$t" }
        $destApk = Join-Path $DistDir "t2lite-$newName-build$newCode-$t.apk"
        Copy-Item -LiteralPath $apk.FullName -Destination $destApk -Force
        $built += $destApk
    }
    $ok = $true
}
finally {
    if (-not $ok) {
        [IO.File]::WriteAllText($VersionFile, $originalVersionText, [Text.Encoding]::ASCII)
        if ($null -ne $originalChangelogText) { [IO.File]::WriteAllText($ChangelogFile, $originalChangelogText, (New-Object Text.UTF8Encoding $false)) }
        Write-Host "Сборка не удалась, версия возвращена к $oldName ($oldCode)." -ForegroundColor Red
    }
}

Write-Step 'Готово'
foreach ($f in $built) {
    $mb = [math]::Round((Get-Item -LiteralPath $f).Length / 1MB, 1)
    Write-Host "  $f  ($mb МБ)" -ForegroundColor Green
}
if ($types -contains 'release') {
    if (Test-Path $KeystorePropsFile) { Write-Host '  release подписан ключом keystore\t2lite-release.jks' }
    else { Write-Host '  release подписан debug-ключом (keystore.properties не найден)' -ForegroundColor Yellow }
}

}
else {
    $t = if ($Type -eq 'debug') { 'debug' } else { 'release' }
    $last = Get-ChildItem -Path $DistDir -Filter "t2lite-*-$t.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $last) { throw "В dist\ нет собранного $t APK. Сначала соберите его (пункт 1 или 2 в меню)." }
    $built = @($last.FullName)
    Write-Step "Без сборки: $($last.Name)"
}

# ---------- установка ----------
if ($Install) {
    $adbExe = Find-Adb
    if (-not $adbExe) { throw 'Не найден adb. Укажите путь: -Adb C:\путь\к\adb.exe' }
    $apkToInstall = $built | Where-Object { $_ -like '*-release.apk' } | Select-Object -First 1
    if (-not $apkToInstall) { $apkToInstall = $built[0] }
    Write-Step "Установка $([IO.Path]::GetFileName($apkToInstall)) (adb: $adbExe)"
    $targets = Select-InstallTargets $adbExe

    $failed = 0
    foreach ($d in $targets) {
        Write-Host "  -> $($d.Name) [$($d.Serial)]"
        $out = Invoke-Adb $adbExe @('-s', $d.Serial, 'install', '-r', $apkToInstall)
        Write-Host ('     ' + $out.Trim())
        if ($out -match 'Success') { continue }
        $failed++
        if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
            Write-Host ("     На устройстве стоит t2 Lite с другой подписью (обычно debug-версия из Android Studio). " +
                "Удалите её: `"$adbExe`" -s $($d.Serial) uninstall ru.myt2lite (вход по SMS придётся пройти заново) и запустите скрипт ещё раз с -Bump none.") -ForegroundColor Yellow
        }
        elseif ($out -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
            Write-Host '     На устройстве версия новее (больше VERSION_CODE). Увеличьте VERSION_CODE в version.properties или удалите приложение.' -ForegroundColor Yellow
        }
        elseif ($out -match 'INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_ABORTED') {
            Write-Host ('     Телефон запретил установку. Подтвердите её на экране телефона; на Xiaomi включите ' +
                '«Для разработчиков» → «Установка через USB», на некоторых телефонах — «Проверять приложения при установке через USB» выключить.') -ForegroundColor Yellow
        }
    }
    if ($failed -gt 0) { Finish 1 }
}
Finish 0
