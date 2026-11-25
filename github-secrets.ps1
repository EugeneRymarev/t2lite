# Добавляет в GitHub 4 секрета, нужных для подписи релизов (см. .github/workflows/release.yml):
#   KEYSTORE_BASE64   - сам файл ключа keystore\t2lite-release.jks, переведённый в текст
#   KEYSTORE_PASSWORD - пароль от файла ключа
#   KEY_ALIAS         - имя ключа внутри файла
#   KEY_PASSWORD      - пароль от самого ключа
# Всё берётся из keystore.properties, который создал build.ps1. Сами значения на экран не выводятся.
#
# Если установлена программа GitHub CLI (gh) и в ней выполнен вход (gh auth login) - секреты добавятся сами.
# Если нет - скрипт по очереди копирует каждое значение в буфер обмена и открывает страницу GitHub,
# куда его нужно вставить (Ctrl+V).
#
# Запуск: двойной щелчок по github-secrets.bat  или  .\github-secrets.ps1 -Repo EugeneRymarev/t2lite
param(
    [string]$Repo = 'EugeneRymarev/t2lite'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$propsFile = Join-Path $PSScriptRoot 'keystore.properties'
if (-not (Test-Path -LiteralPath $propsFile)) {
    throw "Нет файла keystore.properties. Сначала соберите release-версию через build.bat (он создаст ключ) или восстановите ключ из резервной копии."
}
$props = @{}
foreach ($line in [IO.File]::ReadAllLines($propsFile)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
    $i = $t.IndexOf('=')
    if ($i -gt 0) { $props[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim() }
}
foreach ($k in 'storeFile', 'storePassword', 'keyAlias', 'keyPassword') {
    if (-not $props[$k]) { throw "В keystore.properties нет строки $k=..." }
}
$jks = Join-Path $PSScriptRoot ($props['storeFile'] -replace '\\\\', '\' -replace '/', '\')
if (-not (Test-Path -LiteralPath $jks)) { throw "Не найден файл ключа: $jks" }

$secrets = [ordered]@{
    KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
    KEYSTORE_PASSWORD = $props['storePassword']
    KEY_ALIAS         = $props['keyAlias']
    KEY_PASSWORD      = $props['keyPassword']
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
$ghReady = $false
if ($gh) {
    & gh auth status *> $null
    $ghReady = ($LASTEXITCODE -eq 0)
    if (-not $ghReady) { Write-Host "GitHub CLI найден, но вход не выполнен (команда: gh auth login). Добавим секреты вручную." -ForegroundColor Yellow }
}

if ($ghReady) {
    Write-Host "Добавляю секреты в $Repo через GitHub CLI..." -ForegroundColor Cyan
    foreach ($name in $secrets.Keys) {
        # Значение передаётся через стандартный ввод, а не в командной строке, чтобы его не видели другие программы.
        $secrets[$name] | & gh secret set $name --repo $Repo
        if ($LASTEXITCODE -ne 0) { throw "Не удалось добавить секрет $name" }
        Write-Host "  готово: $name" -ForegroundColor Green
    }
}
else {
    $url = "https://github.com/$Repo/settings/secrets/actions/new"
    Write-Host ""
    Write-Host "Сейчас добавим 4 секрета вручную. Для каждого:" -ForegroundColor Cyan
    Write-Host "  1) в браузере откроется страница «New secret»;"
    Write-Host "  2) в поле Name впишите имя, которое покажет этот скрипт;"
    Write-Host "  3) щёлкните в поле Secret и нажмите Ctrl+V (значение уже скопировано);"
    Write-Host "  4) нажмите Add secret и вернитесь сюда."
    foreach ($name in $secrets.Keys) {
        Set-Clipboard -Value $secrets[$name]
        Write-Host ""
        Write-Host "Имя (Name): $name" -ForegroundColor Green
        Write-Host "Значение скопировано в буфер обмена."
        Start-Process $url
        Read-Host "Когда добавите, нажмите Enter" | Out-Null
    }
    Set-Clipboard -Value ' '
    Write-Host "Буфер обмена очищен."
}

Write-Host ""
Write-Host "Готово. Проверить список: https://github.com/$Repo/settings/secrets/actions" -ForegroundColor Cyan
Write-Host "Должны быть: $($secrets.Keys -join ', ')"
