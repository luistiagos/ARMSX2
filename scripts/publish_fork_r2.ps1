<#
.SYNOPSIS
    Build release + publicacao do APK do FORK (ARMSX2-fork) no Cloudflare R2.

.DESCRIPTION
    Este script publica a trilha do FORK, em rgs/ps2fork/. Ele NAO toca em rgs/ps2/,
    que e a trilha da LINHA ANTIGA (feature/handoff-end-to-end) e tem clientes
    instalados -- em 2026-09-02 servia 1.0.23 / versionCode 37.

    AS DUAS TRILHAS SAO INDEPENDENTES, E ESSA E A RAZAO DE ESTE SCRIPT EXISTIR
    ----------------------------------------------------------------------------
    Os dois lados compartilham applicationId (come.nanodata.armsx2) E o mesmo
    certificado de release. Para o Android sao o MESMO app: um APK de uma linha
    instala por cima da outra como upgrade in-place. O que mantem as duas separadas
    nao e o Android, sao tres coisas, e as tres tem gate aqui dentro:

      1. PASTA        rgs/ps2fork/  vs  rgs/ps2/     -- este script so escreve a primeira
      2. ENDPOINT     compilado DENTRO do APK        -- passo 4 le o dex e prova
      3. SERIE        2000, 2001...  vs  37, 38...   -- passo 4 exige o piso

    O item 2 e o que realmente decide. A atualizacao automatica e o unico caminho
    pelo qual uma linha alcanca o aparelho de alguem sem acao humana, e ela consulta
    exatamente uma URL: a que o AppUpdateManager le de BuildConfig.APP_UPDATE_ENDPOINT
    (AppUpdateManager.java:156). Essa URL e compilada dentro do APK -- entao um APK do
    fork consulta rgs/ps2fork/ pelo resto da vida dele, e nenhum engano futuro na hora
    de publicar muda isso.

    O `channel` e a segunda tranca, independente da URL: AppUpdateManager.java:181
    recusa um version.json cujo channel nao bata com o do APK. Fork usa "fork", a
    linha antiga usa "default".

    PASSOS
    ----------------------------------------------------------------------------
    1.  GUARDS DE TRILHA
        Confere que o destino e mesmo a pasta do fork e nunca a da linha antiga, e
        tira uma foto do version.json DELES para provar no passo 10 que nao mudou.

    2.  BUMP DE VERSAO
        Incrementa armsx2.versionCode (+1) e o patch de armsx2.versionName em
        platforms/android/gradle.properties ANTES de compilar. -NoBump nao mexe;
        -VersionName 2.1.0 faz um salto que nao seja de patch.

    3.  BUILD
        gradlew :app:assembleGithubRelease, com o JDK 21 pinado (sem isso o Gradle
        auto-detecta um JRE de extensao de editor, sem jlink, e o build falha com um
        erro que nao tem nada a ver com o codigo).

    4.  VERIFICACAO DO APK (gate -- aborta a publicacao se falhar)
        a) assinado com a chave de release oficial (cert SHA-256 fixo abaixo);
        b) versionCode/versionName lidos do PROPRIO APK, nao do gradle.properties;
        c) applicationId exato e versionName no padrao X.Y.Z (barra builds .perf/.debug);
        d) versao do APK == versao do gradle.properties (barra APK sobrando em dist\);
        e) versionCode >= 2000 -- piso da serie do fork;
        f) o endpoint compilado no dex e o do FORK, e o da linha antiga NAO aparece.
        O (f) e o unico jeito de provar que este APK vai consultar a pasta certa.
        Ler o build.gradle.kts nao prova nada: o APK em outputs/ pode ser de um build
        anterior a mudanca.

    5.  UPLOAD - HISTORICO (versionado, nunca sobrescreve)
        rgs/ps2fork/history/retrosystem-ps2-<versionName>-<versionCode>.apk
        Se a versao ja existe no remoto com bytes diferentes, aborta (use -Force).

    6.  UPLOAD - DISTRIBUICAO (nome estavel, sempre sobrescreve)
        rgs/ps2fork/retrosystem-ps2.apk        <- URL publica que o cliente baixa
        rgs/ps2fork/retrosystem-ps2.apk.sha256 <- suporte descarta corrupcao na hora
        O nome do arquivo e o MESMO da linha antiga de proposito; o que separa as
        duas e a pasta.

    7.  VERIFICACAO POS-UPLOAD (origem R2): tamanho E MD5 de cada objeto remoto.

    8.  ANUNCIO (rgs/ps2fork/version.json)
        E o arquivo que o AppUpdateManager le para saber que saiu versao nova.
        Enviado por ULTIMO entre os artefatos: e ele que dispara a atualizacao nos
        apps instalados, entao so vai ao ar depois do APK verificado no passo 7.

    9.  PURGA DO CACHE DE BORDA (Cloudflare)
        Sem isto o link que circula com os clientes continua entregando o APK da
        versao anterior por tempo indeterminado. Precisa de CF_API_TOKEN + CF_ZONE_ID
        em build.properties; sem eles o passo e pulado com aviso (nao falha).

    10. VERIFICACAO PUBLICA + PROVA DE NAO-INTERFERENCIA
        Baixa a URL que o app usa e confere o SHA-256 -- e o unico passo que prova o
        que o CLIENTE recebe (o passo 7 fala com o R2 pela API S3 e pula o cache de
        borda). Depois re-le o version.json da LINHA ANTIGA e exige que esteja
        identico a foto do passo 1.

.PARAMETER SkipBuild
    Pula o build e usa o APK existente em dist\. Os gates do passo 4 continuam
    valendo -- inclusive o do endpoint, que e justamente o que pega um APK velho.

.PARAMETER SkipHistory
    Nao envia a copia versionada (so atualiza a de distribuicao).

.PARAMETER DryRun
    Faz build e todas as verificacoes locais e mostra o que seria enviado, sem
    escrever nada no R2 e sem alterar o gradle.properties.

.PARAMETER Force
    Permite sobrescrever uma versao ja existente no history/ com bytes diferentes.

.PARAMETER NoBump
    Nao incrementa a versao (usa a que estiver no gradle.properties).

.PARAMETER VersionName
    versionName explicito para esta publicacao (ex: -VersionName 2.1.0).
    Sem isto, o patch e incrementado: 2.0.0 -> 2.0.1.

.PARAMETER JdkHome
    JDK 21 usado pelo Gradle. Default D:\DevCaches\jdk-21.

.PARAMETER KeepDaemon
    Nao roda `gradlew --stop` antes do build. Por padrao o daemon e derrubado: um
    daemon vivo de uma invocacao anterior sem as flags de JDK e reaproveitado, e a
    falha resultante nao se parece com a causa.

.EXAMPLE
    .\scripts\publish_fork_r2.ps1 -DryRun
    Build + todos os gates, sem publicar nada. Rode isto primeiro.

.EXAMPLE
    .\scripts\publish_fork_r2.ps1 -NoBump
    A PRIMEIRA publicacao do fork. O bump roda ANTES do build (tem de rodar), entao
    sem -NoBump a primeira versao no ar seria 2.0.1/2001 e o 2.0.0/2000 que esta no
    gradle.properties nunca existiria. Da segunda em diante, rode sem -NoBump.

.EXAMPLE
    .\scripts\publish_fork_r2.ps1
    Publicacao completa: bump, build, gates, historico, distribuicao, anuncio.

.EXAMPLE
    .\scripts\publish_fork_r2.ps1 -SkipBuild -NoBump
    Republica o APK que ja esta em dist\ (util se um upload falhou no meio).

.NOTES
    TASK-0075. Pre-requisitos:
    - JDK 21 (D:\DevCaches\jdk-21)
    - platforms\android\armsx2_keystore.properties + retrosystem_release.jks
    - rclone (D:\DevCaches\rclone\rclone.exe, ou no PATH, ou via winget)
    - Android SDK build-tools (apksigner + aapt), achado via ANDROID_HOME
    - Credenciais R2: R2_* em build.properties, ou r2-config.json na raiz, ou
      fallback para D:\projects\GODSend\r2-config.json (mesma conta R2)
#>

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipHistory,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$NoBump,
    [string]$VersionName = "",
    [string]$JdkHome = "D:\DevCaches\jdk-21",
    [switch]$KeepDaemon
)

$ErrorActionPreference = "Stop"

# =====================================================================
#  CONFIG
# =====================================================================

$SCRIPT_DIR   = Split-Path -Parent $MyInvocation.MyCommand.Path
$PROJECT_ROOT = Split-Path -Parent $SCRIPT_DIR
$ANDROID_DIR  = Join-Path $PROJECT_ROOT "platforms\android"
$GRADLE_PROPS = Join-Path $ANDROID_DIR "gradle.properties"
$GRADLEW      = Join-Path $ANDROID_DIR "gradlew.bat"
$KEYSTORE_PROPS = Join-Path $ANDROID_DIR "armsx2_keystore.properties"
$BUILT_APK    = Join-Path $ANDROID_DIR "app\build\outputs\apk\github\release\app-github-release.apk"

$DIST_DIR     = Join-Path $PROJECT_ROOT "dist"
# MESMO nome de arquivo da linha antiga, de proposito -- o que separa as duas
# trilhas e a PASTA, nao o nome. Mudar isto quebra o link que ja circula.
$APK_NAME     = "retrosystem-ps2.apk"
$LOCAL_APK    = Join-Path $DIST_DIR $APK_NAME
$ENV_FILE     = Join-Path $PROJECT_ROOT "build.properties"

$PUBLIC_BASE  = "https://versions.digitalstoregames.com"

# --- A trilha do FORK -------------------------------------------------
$R2_FOLDER      = "rgs/ps2fork"
$R2_HISTORY     = "rgs/ps2fork/history"
$UPDATE_CHANNEL = "fork"
$UPDATE_ENDPOINT = "$PUBLIC_BASE/$R2_FOLDER/version.json"
# Piso da serie do fork. Um numero abaixo disto e da serie da linha antiga e nao
# tem o que fazer nesta pasta.
$MIN_VERSION_CODE = 2000
# Identidade que um APK publicavel tem de ter. Sufixos (.perf, .debug) vem de
# builds de teste e instalam como OUTRO app -- ver o gate 4c.
$EXPECTED_APPLICATION_ID = "come.nanodata.armsx2"

# --- A trilha da LINHA ANTIGA -- so para NAO tocar --------------------
# Nada neste script escreve aqui. Os valores existem para os guards do passo 1
# e para a prova de nao-interferencia do passo 10.
$LEGACY_FOLDER   = "rgs/ps2"
$LEGACY_ENDPOINT = "$PUBLIC_BASE/$LEGACY_FOLDER/version.json"

# Chave de release oficial. NAO altere sem trocar o keystore de proposito -- esse
# valor e o que impede um APK mal-assinado de chegar nos usuarios.
#   platforms\android\retrosystem_release.jks, alias "retrosystem"
# O release do build.gradle.kts cai em signingConfigs "debug" SEM FALHAR quando
# armsx2_keystore.properties nao resolve; este gate e o que pega isso.
$EXPECTED_CERT_SHA256 = "d34a788ab0f4fb5b467be5839c4317d66a46525397dfeebdeb40ba4b97c0745a"

# Configuracao R2 (mesma cascata do Lemuroid/GODSend)
$LOCAL_R2_CONFIG   = Join-Path $PROJECT_ROOT "r2-config.json"
$GODSEND_R2_CONFIG = "D:\projects\GODSend\r2-config.json"

# =====================================================================
#  HELPERS
# =====================================================================

function Print-Step {
    param([string]$Message, [string]$Color = "Cyan")
    Write-Host ""
    Write-Host "========================================================" -ForegroundColor $Color
    Write-Host "  $Message" -ForegroundColor $Color
    Write-Host "========================================================" -ForegroundColor $Color
}

# No PowerShell 5.1, com ErrorActionPreference=Stop, QUALQUER coisa que um
# executavel nativo escreva no stderr vira NativeCommandError e derruba o script
# mesmo com exit code 0. Gradle e rclone escrevem progresso no stderr o tempo
# todo. Estes dois helpers isolam a chamada e decidem pelo exit code, que e o
# unico sinal confiavel.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($code -ne 0) { throw "$What falhou (exit code $code)" }
}

function Invoke-NativeCapture {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($code -ne 0) {
        Write-Host ($out | Out-String) -ForegroundColor DarkGray
        throw "$What falhou (exit code $code)"
    }
    return $out
}

function Find-Rclone {
    $candidates = @("D:\DevCaches\rclone\rclone.exe")
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { return $c }
    }
    $cmd = Get-Command rclone -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $winget = Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter "rclone.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($winget) { return $winget }
    throw "rclone.exe nao encontrado. Instale com: winget install Rclone.Rclone (e reabra o shell)."
}

# Resolve uma ferramenta do build-tools pegando sempre a versao mais recente.
function Find-BuildTool {
    param([string]$ToolName, [switch]$Optional)

    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                  "D:\DevCaches\Android\Sdk",
                  (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $sdkRoots) {
        $btRoot = Join-Path $root "build-tools"
        if (-not (Test-Path -LiteralPath $btRoot)) { continue }

        $versions = Get-ChildItem -LiteralPath $btRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object -Property @{ Expression = {
                $parsed = [version]"0.0.0"
                if ([version]::TryParse($_.Name, [ref]$parsed)) { $parsed } else { [version]"0.0.0" }
            }} -Descending

        foreach ($v in $versions) {
            $candidate = Join-Path $v.FullName $ToolName
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }

    if ($Optional) { return $null }
    throw "$ToolName nao encontrado no build-tools. Defina ANDROID_HOME apontando para o Android SDK."
}

# Le o pool de strings dos classes*.dex do APK e procura uma agulha ASCII.
#
# Por que funciona: as strings do dex sao MUTF-8 e ficam gravadas literalmente. O
# R8 inlina constantes `static final String` no ponto de uso, mas a constante
# continua no pool. Decodificar em ISO-8859-1 preserva byte-por-char, entao
# IndexOf ordinal sobre uma agulha ASCII acha exatamente o que existe nos bytes.
#
# [System.Text.Encoding]::Latin1 NAO existe no .NET Framework do PowerShell 5.1;
# GetEncoding(28591) e o mesmo codec e existe nos dois.
function Test-ApkContainsString {
    param([string]$ApkPath, [string]$Needle)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $latin1 = [System.Text.Encoding]::GetEncoding(28591)
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $ms = New-Object System.IO.MemoryStream
            $stream = $entry.Open()
            try { $stream.CopyTo($ms) } finally { $stream.Dispose() }
            $text = $latin1.GetString($ms.ToArray())
            $ms.Dispose()
            if ($text.IndexOf($Needle, [System.StringComparison]::Ordinal) -ge 0) { return $true }
        }
    } finally {
        $zip.Dispose()
    }
    return $false
}

# Sobe armsx2.versionCode (+1) e o patch de armsx2.versionName no gradle.properties.
#
# TEM que rodar antes do gradle: se rodasse depois, o APK sairia com a versao
# antiga e o passo 4 (que le a versao do proprio APK) acusaria a divergencia.
#
# versionName sobe junto de proposito. Se so o code subisse, o dialogo no celular
# ficaria "A versao 2.0.0 esta disponivel. Voce tem a 2.0.0."
function Invoke-VersionBump {
    if ($NoBump) {
        Write-Host "Bump de versao: PULADO (-NoBump)" -ForegroundColor Yellow
        return
    }

    $raw = [System.IO.File]::ReadAllText($GRADLE_PROPS)

    # Ancorado em `armsx2.` para nao casar com nenhuma outra chave, e sem ancora $
    # de proposito: com CRLF o $ multiline exige estar logo antes do \n, mas
    # [^\r\n]* nao consome o \r e o casamento falharia sempre.
    $codeLine = [regex]::Match($raw, '(?m)^\s*armsx2\.versionCode\s*=[^\r\n]*').Value
    $nameLine = [regex]::Match($raw, '(?m)^\s*armsx2\.versionName\s*=[^\r\n]*').Value
    if (-not $codeLine -or -not $nameLine) {
        throw "Nao encontrei armsx2.versionCode / armsx2.versionName em $GRADLE_PROPS"
    }

    $oldCode = [int]([regex]::Match($codeLine, '=\s*(\d+)').Groups[1].Value)
    $newCode = $oldCode + 1

    $oldName = [regex]::Match($nameLine, '=\s*(.+?)\s*$').Groups[1].Value

    if ($VersionName) {
        $newName = $VersionName
    } elseif ($oldName -match '^(\d+)\.(\d+)\.(\d+)$') {
        $newName = "$($matches[1]).$($matches[2]).$([int]$matches[3] + 1)"
    } else {
        throw "versionName '$oldName' nao segue X.Y.Z; passe -VersionName <valor>."
    }

    if ($newCode -lt $MIN_VERSION_CODE) {
        throw @"
BUMP RECUSADO: o novo versionCode seria $newCode, abaixo do piso da serie do fork ($MIN_VERSION_CODE).
Um numero abaixo de $MIN_VERSION_CODE pertence a serie da LINHA ANTIGA (37, 38, 39...).
Confira armsx2.versionCode em $GRADLE_PROPS.
"@
    }

    Write-Host "  versionCode: $oldCode -> $newCode" -ForegroundColor White
    Write-Host "  versionName: $oldName -> $newName" -ForegroundColor White

    if ($DryRun) {
        Write-Host "  [DRY-RUN] gradle.properties nao foi alterado." -ForegroundColor Magenta
        return
    }

    $newCodeLine = $codeLine -replace '=\s*\d+', "=$newCode"
    $newNameLine = $nameLine -replace '=\s*.+$', "=$newName"
    # Replace no texto bruto (e nao linha a linha) preserva as quebras de linha
    # originais -- senao o diff viraria o arquivo inteiro.
    $updated = $raw.Replace($codeLine, $newCodeLine).Replace($nameLine, $newNameLine)
    [System.IO.File]::WriteAllText($GRADLE_PROPS, $updated, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  gradle.properties atualizado" -ForegroundColor Green
}

function Get-R2Config {
    if ($Script:R2_ACCESS_KEY_ID -and $Script:R2_SECRET_ACCESS_KEY -and $Script:R2_ENDPOINT -and $Script:R2_BUCKET) {
        return [PSCustomObject]@{
            accessKeyId     = $Script:R2_ACCESS_KEY_ID
            secretAccessKey = $Script:R2_SECRET_ACCESS_KEY
            endpoint        = $Script:R2_ENDPOINT
            bucket          = $Script:R2_BUCKET
            source          = "build.properties"
        }
    }
    foreach ($path in @($LOCAL_R2_CONFIG, $GODSEND_R2_CONFIG)) {
        if (Test-Path -LiteralPath $path) {
            $c = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
            $c | Add-Member -NotePropertyName source -NotePropertyValue $path -Force
            return $c
        }
    }
    throw "Credenciais R2 nao encontradas. Defina R2_* em build.properties, crie r2-config.json na raiz, ou garanta que $GODSEND_R2_CONFIG existe."
}

# build.properties e opcional (so precisa se nao quiser usar r2-config.json, ou
# para CF_API_TOKEN / CF_ZONE_ID do passo 9).
if (Test-Path -LiteralPath $ENV_FILE) {
    Get-Content -LiteralPath $ENV_FILE -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)\s*$') {
            $k = $matches[1].Trim()
            $v = $matches[2].Trim().Trim('"', "'")
            Set-Variable -Name $k -Value $v -Scope Script
        }
    }
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# =====================================================================
#  PASSO 1: GUARDS DE TRILHA
# =====================================================================
Print-Step "PASSO 1/10: Guards de trilha (fork vs linha antiga)"

# Guards de configuracao. Sao baratos e pegam a edicao distraida deste proprio
# arquivo -- que e o unico jeito de este script encostar na linha antiga.
#
# Cada um checa uma coisa so, de proposito: um guard composto e dificil de ler e
# facil de escrever errado, e este e justamente o codigo que nao pode ter engano.
if ($R2_FOLDER -eq $LEGACY_FOLDER) {
    throw "GUARD: R2_FOLDER e a pasta da LINHA ANTIGA ('$LEGACY_FOLDER'). Este script nunca publica ali."
}
if ($R2_FOLDER -ne "rgs/ps2fork") {
    throw "GUARD: R2_FOLDER esperado 'rgs/ps2fork', encontrado '$R2_FOLDER'."
}
if ($R2_HISTORY -notlike "$R2_FOLDER/*") {
    throw "GUARD: R2_HISTORY ('$R2_HISTORY') tem de ficar dentro de '$R2_FOLDER'."
}
if ($UPDATE_CHANNEL -eq "default") {
    throw "GUARD: o canal 'default' e o da linha antiga. O fork usa outro."
}
if ($UPDATE_ENDPOINT -ne "$PUBLIC_BASE/$R2_FOLDER/version.json") {
    throw "GUARD: UPDATE_ENDPOINT ('$UPDATE_ENDPOINT') nao aponta para a pasta que este script publica."
}
Write-Host "  Trilha do fork:        $PUBLIC_BASE/$R2_FOLDER/" -ForegroundColor Green
Write-Host "  Canal:                 $UPDATE_CHANNEL" -ForegroundColor Green
Write-Host "  Piso da serie:         versionCode >= $MIN_VERSION_CODE" -ForegroundColor Green
Write-Host "  Linha antiga (NAO tocar): $PUBLIC_BASE/$LEGACY_FOLDER/" -ForegroundColor Yellow

# Foto do version.json DELES, para o passo 10 provar que nao mudou.
$legacyBefore = $null
try {
    $legacyBefore = Invoke-RestMethod -Uri $LEGACY_ENDPOINT -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 30
    Write-Host ("  Linha antiga esta em:  {0} (code {1}) -- deve continuar assim no passo 10" -f `
        $legacyBefore.versionName, $legacyBefore.versionCode) -ForegroundColor Yellow
} catch {
    Write-Host "  AVISO: nao consegui ler o version.json da linha antiga ($($_.Exception.Message))." -ForegroundColor Yellow
    Write-Host "         A prova de nao-interferencia do passo 10 sera pulada." -ForegroundColor Yellow
}

# =====================================================================
#  PASSO 2 + 3: BUMP E BUILD
# =====================================================================
if (-not $SkipBuild) {
    Print-Step "PASSO 2/10: Bump de versao"
    Invoke-VersionBump

    Print-Step "PASSO 3/10: Build release (github flavor)"

    if (-not (Test-Path -LiteralPath $GRADLEW)) { throw "gradlew.bat nao encontrado: $GRADLEW" }
    if (-not (Test-Path -LiteralPath $JdkHome)) {
        throw "JDK 21 nao encontrado em '$JdkHome'. Passe -JdkHome <caminho>."
    }
    # O release cai em signingConfigs "debug" SEM FALHAR quando este arquivo nao
    # existe. O gate do passo 4 pega, mas falhar aqui poupa ~15 min de build.
    if (-not (Test-Path -LiteralPath $KEYSTORE_PROPS)) {
        throw @"
$KEYSTORE_PROPS nao existe.
Sem ele o build.gradle.kts assina o release com a keystore de DEBUG e NAO falha ao
fazer isso. Um APK com assinatura diferente quebra a atualizacao de todos os
instalados, e a recuperacao e desinstalar (perdendo saves).
"@
    }

    $savedJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $JdkHome
    try {
        # Um daemon vivo de uma invocacao anterior SEM as flags de JDK e
        # reaproveitado com o JVM errado, e a falha resultante nao se parece com a
        # causa: aparece como "Could not resolve all files for configuration
        # ':app:androidJdkImage'", e so na primeira tarefa que realmente compila Java.
        if (-not $KeepDaemon) {
            Write-Host "Derrubando daemons do Gradle (evita reaproveitar um com o JVM errado)..." -ForegroundColor Yellow
            $saved = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & $GRADLEW "--stop" | Out-Null } finally { $ErrorActionPreference = $saved }
        }

        # Cada -D precisa das proprias aspas: sem elas o PowerShell quebra o
        # argumento no ponto e o Gradle reclama de
        # "Task '.gradle.java.installations.auto-detect=false' not found".
        #
        # updateEndpoint/updateChannel sao passados EXPLICITAMENTE mesmo ja sendo os
        # defaults do build.gradle.kts: assim o valor publicado e o valor construido
        # saem da MESMA variavel deste script, e nao de dois arquivos que podem
        # divergir sem ninguem notar.
        $gradleArgs = @(
            ":app:assembleGithubRelease",
            "-Parmsx2.updateEndpoint=$UPDATE_ENDPOINT",
            "-Parmsx2.updateChannel=$UPDATE_CHANNEL",
            '-Dorg.gradle.java.installations.auto-detect=false',
            "-Dorg.gradle.java.installations.paths=$JdkHome"
        )
        Write-Host "JAVA_HOME=$JdkHome" -ForegroundColor DarkGray
        Write-Host "gradlew $($gradleArgs -join ' ')" -ForegroundColor DarkGray

        Push-Location $ANDROID_DIR
        try {
            Invoke-Native -Exe $GRADLEW -Arguments $gradleArgs -What "assembleGithubRelease"
        } finally {
            Pop-Location
        }
    } finally {
        $env:JAVA_HOME = $savedJavaHome
    }

    if (-not (Test-Path -LiteralPath $BUILT_APK)) {
        throw "Build terminou sem erro mas o APK nao esta em $BUILT_APK"
    }

    if (-not (Test-Path -LiteralPath $DIST_DIR)) { New-Item -ItemType Directory -Path $DIST_DIR | Out-Null }
    Copy-Item -LiteralPath $BUILT_APK -Destination $LOCAL_APK -Force
    Write-Host "APK: $LOCAL_APK" -ForegroundColor Green
} else {
    Print-Step "PASSO 2-3/10: Bump e build (SKIPPED -- usando o APK em dist\)"
    # Sem build nao ha bump: o APK em dist\ ja carrega a versao dele, e subir o
    # gradle.properties aqui so criaria divergencia entre o repo e o publicado.
    Write-Host "Bump de versao: PULADO (-SkipBuild)" -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $LOCAL_APK)) {
    throw "APK nao encontrado: $LOCAL_APK`nExecute sem -SkipBuild."
}

# =====================================================================
#  PASSO 4: VERIFICACAO DO APK (gate)
# =====================================================================
Print-Step "PASSO 4/10: Verificacao do APK (assinatura, serie, endpoint)"

$apksigner = Find-BuildTool "apksigner.bat"

# --- 4a: assinatura ---------------------------------------------------
Write-Host "Conferindo assinatura..." -ForegroundColor Yellow
$signerOutput = Invoke-NativeCapture -Exe $apksigner -Arguments @("verify", "--print-certs", $LOCAL_APK) -What "apksigner verify"

$certLine = $signerOutput | Select-String -Pattern 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'
if (-not $certLine) {
    throw "Nao foi possivel ler o SHA-256 do certificado na saida do apksigner. Nada foi publicado."
}
$actualCert = $certLine.Matches[0].Groups[1].Value.ToLower()

if ($actualCert -ne $EXPECTED_CERT_SHA256.ToLower()) {
    Write-Host ""
    Write-Host "  ASSINATURA ERRADA - PUBLICACAO ABORTADA" -ForegroundColor Red
    Write-Host "  esperado: $EXPECTED_CERT_SHA256" -ForegroundColor Red
    Write-Host "  obtido:   $actualCert" -ForegroundColor Red
    Write-Host ""
    Write-Host "  O build.gradle.kts assina o release com a keystore de DEBUG, e NAO" -ForegroundColor Yellow
    Write-Host "  falha ao fazer isso, quando armsx2_keystore.properties nao resolve." -ForegroundColor Yellow
    Write-Host "  Confira $KEYSTORE_PROPS e o retrosystem_release.jks." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Publicar este APK quebraria a atualizacao de TODOS os instalados" -ForegroundColor Red
    Write-Host "  (so sairiam desinstalando, perdendo saves)." -ForegroundColor Red
    throw "Verificacao de assinatura falhou. Nada foi publicado."
}
Write-Host "  OK: assinado com a chave de release oficial." -ForegroundColor Green

# --- 4b: versao lida do proprio artefato ------------------------------
$aapt = Find-BuildTool "aapt.exe" -Optional
if ($aapt) {
    $badging = Invoke-NativeCapture -Exe $aapt -Arguments @("dump", "badging", $LOCAL_APK) -What "aapt dump badging"
} else {
    # aapt e legado e pode sumir de um build-tools novo; o aapt2 emite a mesma
    # linha `package: name=... versionCode=... versionName=...`.
    $aapt2 = Find-BuildTool "aapt2.exe"
    $badging = Invoke-NativeCapture -Exe $aapt2 -Arguments @("dump", "badging", $LOCAL_APK) -What "aapt2 dump badging"
}
$pkgLine = $badging | Select-String -Pattern "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'"
if (-not $pkgLine) {
    throw "Nao foi possivel ler package/versionCode/versionName do APK."
}
$PACKAGE_ID   = $pkgLine.Matches[0].Groups[1].Value
$VERSION_CODE = [int]$pkgLine.Matches[0].Groups[2].Value
$VERSION_NAME = $pkgLine.Matches[0].Groups[3].Value

$apkItem   = Get-Item -LiteralPath $LOCAL_APK
$apkSize   = $apkItem.Length
$apkSha256 = (Get-FileHash -LiteralPath $LOCAL_APK -Algorithm SHA256).Hash.ToLower()
$apkMd5    = (Get-FileHash -LiteralPath $LOCAL_APK -Algorithm MD5).Hash.ToLower()

Write-Host "  Pacote:  $PACKAGE_ID" -ForegroundColor White
Write-Host "  Versao:  $VERSION_NAME (code $VERSION_CODE)" -ForegroundColor White
Write-Host "  Tamanho: $apkSize bytes ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor White
Write-Host "  SHA-256: $apkSha256" -ForegroundColor White

# --- 4c: identidade do pacote ----------------------------------------
# Medido em 2026-09-02: o APK que estava em outputs/ era um build de perf-test --
# come.nanodata.armsx2.perf, 0.0.0-perftest, versionCode 9001. Passou pela
# assinatura e pelo piso da serie (9001 >= 2000) sem reclamar. Publicar aquilo
# instalaria um app DIFERENTE (outro applicationId), que updater nenhum enxerga,
# e queimaria o numero 9001 na serie -- depois disso um 2001 seria recusado pelo
# Android para sempre naquele aparelho.
if ($PACKAGE_ID -ne $EXPECTED_APPLICATION_ID) {
    throw @"
APPLICATION ID ERRADO - PUBLICACAO ABORTADA
  esperado: $EXPECTED_APPLICATION_ID
  obtido:   $PACKAGE_ID

Um applicationId diferente instala como OUTRO app: ninguem que ja tem o fork
recebe esta versao, e o updater dela nao alcanca ninguem. Sufixos como `.perf`
ou `.debug` vem de builds de teste (applicationIdSuffix) que nao devem ser
publicados.
"@
}
if ($VERSION_NAME -notmatch '^\d+\.\d+\.\d+$') {
    throw @"
VERSION NAME FORA DO PADRAO - PUBLICACAO ABORTADA
  obtido: '$VERSION_NAME' (esperado X.Y.Z)

Nomes como '0.0.0-perftest' vem de builds de teste. O versionName aparece no
dialogo de atualizacao do usuario e no nome do arquivo no history/.
"@
}
Write-Host "  OK: identidade confere ($EXPECTED_APPLICATION_ID, versionName no padrao X.Y.Z)." -ForegroundColor Green

# --- 4d: o APK e o que este repositorio descreve ----------------------
# Ancora o artefato no repositorio. E o que pega um APK sobrando em dist\ de uma
# publicacao anterior, ou um build feito com -PversionCodeOverride.
$propsCode = [int]([regex]::Match([System.IO.File]::ReadAllText($GRADLE_PROPS), '(?m)^\s*armsx2\.versionCode\s*=\s*(\d+)').Groups[1].Value)
$propsName = [regex]::Match([System.IO.File]::ReadAllText($GRADLE_PROPS), '(?m)^\s*armsx2\.versionName\s*=\s*(.+?)\s*$').Groups[1].Value
if ($VERSION_CODE -ne $propsCode -or $VERSION_NAME -ne $propsName) {
    throw @"
O APK NAO E O QUE ESTE REPOSITORIO DESCREVE - PUBLICACAO ABORTADA
  APK:              $VERSION_NAME (code $VERSION_CODE)
  gradle.properties: $propsName (code $propsCode)

O APK em dist\ foi construido de outra configuracao -- outra publicacao, ou um
build com override na linha de comando. Publicar assim faz o repositorio e o que
esta no ar contarem historias diferentes, e o proximo bump parte do numero errado.
Rode sem -SkipBuild.
"@
}
Write-Host "  OK: o APK bate com o gradle.properties ($propsName, code $propsCode)." -ForegroundColor Green

# --- 4e: piso da serie ------------------------------------------------
if ($VERSION_CODE -lt $MIN_VERSION_CODE) {
    throw @"
SERIE ERRADA - PUBLICACAO ABORTADA
  versionCode do APK: $VERSION_CODE
  piso do fork:       $MIN_VERSION_CODE

Um numero abaixo de $MIN_VERSION_CODE pertence a serie da LINHA ANTIGA (37, 38, 39...).
As duas series sao disjuntas de proposito: os dois lados compartilham applicationId
e certificado, entao dois APKs com o mesmo versionCode seriam indistinguiveis para
o Android, para o suporte e para a telemetria.
Confira armsx2.versionCode em $GRADLE_PROPS.
"@
}
Write-Host "  OK: versionCode $VERSION_CODE esta na serie do fork (>= $MIN_VERSION_CODE)." -ForegroundColor Green

# --- 4f: o endpoint compilado DENTRO do APK ---------------------------
# O gate que decide. Um APK so consulta a URL que foi compilada nele; se esta
# errada, nenhuma correcao futura no script conserta os aparelhos que ja
# instalaram -- so um APK novo conserta.
Write-Host "Conferindo o endpoint de atualizacao compilado no APK..." -ForegroundColor Yellow

$hasForkEndpoint   = Test-ApkContainsString -ApkPath $LOCAL_APK -Needle $UPDATE_ENDPOINT
$hasLegacyEndpoint = Test-ApkContainsString -ApkPath $LOCAL_APK -Needle $LEGACY_ENDPOINT

if ($hasLegacyEndpoint) {
    throw @"
ENDPOINT DA LINHA ANTIGA DENTRO DO APK - PUBLICACAO ABORTADA
  encontrado no dex: $LEGACY_ENDPOINT

Este APK consultaria o version.json da LINHA ANTIGA. Todo mundo que o instalasse
passaria a receber as atualizacoes DELES, e isso e permanente: o endpoint e
compilado dentro do APK.

Normalmente e um APK de um build anterior a TASK-0075. Rode sem -SkipBuild.
"@
}
if (-not $hasForkEndpoint) {
    throw @"
ENDPOINT DO FORK AUSENTE NO APK - PUBLICACAO ABORTADA
  esperado no dex: $UPDATE_ENDPOINT

Sem essa string o APK nao consulta a trilha do fork. Confira
APP_UPDATE_ENDPOINT no flavor github de app/build.gradle.kts e rode sem -SkipBuild.
"@
}
Write-Host "  OK: o APK consulta $UPDATE_ENDPOINT" -ForegroundColor Green
Write-Host "  OK: o endpoint da linha antiga NAO esta no APK." -ForegroundColor Green

# --- 4g: artefatos de anuncio ----------------------------------------
# Sidecar de hash: deixa o suporte descartar download corrompido em segundos.
$sha256File = Join-Path $DIST_DIR "$APK_NAME.sha256"
"$apkSha256  $APK_NAME" | Out-File -FilePath $sha256File -Encoding ascii -Force

# O "?v=<versionCode>" NAO e enfeite. O cache de borda na frente do R2 continua
# servindo os bytes antigos na URL canonica por tempo indeterminado depois do
# upload -- e ignora Cache-Control/no-cache do cliente. Medido na linha antiga em
# 2026-08-10: o version.json ja anunciava 1.0.9 enquanto a URL do APK ainda
# devolvia o 1.0.8. A query string muda a chave de cache, entao cada release
# busca bytes frescos. Sem isso o app baixa a versao errada, o SHA-256 nao bate e
# a atualizacao entra em loop de erro ate o cache expirar sozinho.
$publicApkUrl = "$PUBLIC_BASE/$R2_FOLDER/$($APK_NAME)?v=$($VERSION_CODE)"

$versionJsonFile = Join-Path $DIST_DIR "version.json"
$versionPayload = [ordered]@{
    versionCode = [int]$VERSION_CODE
    versionName = $VERSION_NAME
    channel     = $UPDATE_CHANNEL
    apkUrl      = $publicApkUrl
    sha256      = $apkSha256
    size        = [long]$apkSize
} | ConvertTo-Json -Depth 3
$versionPayload | Out-File -FilePath $versionJsonFile -Encoding ascii -Force

$historyName = "retrosystem-ps2-$VERSION_NAME-$VERSION_CODE.apk"

# =====================================================================
#  R2: config + flags
# =====================================================================
$cfg = Get-R2Config
foreach ($field in @('accessKeyId', 'secretAccessKey', 'endpoint', 'bucket')) {
    if (-not $cfg.$field) { throw "Config R2 faltando campo obrigatorio: $field (fonte: $($cfg.source))" }
}

$rclone  = Find-Rclone
# --log-level=ERROR silencia o NOTICE de "config file not found" no stderr.
$s3Flags = @(
    "--log-level=ERROR",
    "--s3-provider=Cloudflare",
    "--s3-access-key-id=$($cfg.accessKeyId)",
    "--s3-secret-access-key=$($cfg.secretAccessKey)",
    "--s3-endpoint=$($cfg.endpoint)",
    "--s3-no-check-bucket"
)
# Sem isso o R2 pode servir o APK como application/octet-stream e alguns
# navegadores Android salvam o arquivo com nome/tratamento errado.
$apkHeaders = @("--header-upload=Content-Type: application/vnd.android.package-archive")
$txtHeaders = @("--header-upload=Content-Type: text/plain; charset=utf-8")

$distDest    = ":s3:$($cfg.bucket)/$R2_FOLDER"
$historyDest = ":s3:$($cfg.bucket)/$R2_HISTORY"

# Ultimo guard, agora sobre o destino ja montado: nenhuma das strings que vao
# para o rclone pode apontar para a pasta da linha antiga.
foreach ($dest in @($distDest, $historyDest)) {
    if ($dest -match "/$LEGACY_FOLDER(/|$)") {
        throw "GUARD: destino '$dest' cai na pasta da LINHA ANTIGA. Abortado."
    }
}

Write-Host ""
Write-Host "R2: bucket '$($cfg.bucket)' (credenciais de $($cfg.source))" -ForegroundColor Yellow
Write-Host "    destino: $distDest" -ForegroundColor Yellow

# =====================================================================
#  PASSO 5: UPLOAD - HISTORICO
# =====================================================================
if (-not $SkipHistory) {
    Print-Step "PASSO 5/10: Upload historico (versionado)"

    $existingRaw = Invoke-NativeCapture -Exe $rclone -Arguments (@("lsjson", $historyDest) + $s3Flags + @("--hash")) -What "rclone lsjson (historico)"
    $existing = $existingRaw | ConvertFrom-Json

    $clash = $existing | Where-Object { -not $_.IsDir -and $_.Name -eq $historyName } | Select-Object -First 1
    if ($clash) {
        $remoteMd5 = $null
        if ($clash.Hashes -and $clash.Hashes.md5) { $remoteMd5 = $clash.Hashes.md5.ToLower() }

        if ($remoteMd5 -eq $apkMd5) {
            Write-Host "  Versao $VERSION_NAME ($VERSION_CODE) ja publicada no history/ com os mesmos bytes. Pulando." -ForegroundColor Green
            $SkipHistory = $true
        } elseif (-not $Force) {
            throw @"
$historyName ja existe no history/ com bytes DIFERENTES.
  remoto: $remoteMd5 ($($clash.Size) bytes)
  local:  $apkMd5 ($apkSize bytes)
A versao $VERSION_NAME (code $VERSION_CODE) ja foi publicada com outro conteudo.
Suba a versao em $GRADLE_PROPS, ou use -Force para sobrescrever o historico.
Nada foi publicado.
"@
        } else {
            Write-Host "  -Force: sobrescrevendo $historyName no historico." -ForegroundColor Yellow
        }
    }

    if (-not $SkipHistory) {
        Write-Host "Enviando $historyName -> $($cfg.bucket)/$R2_HISTORY ..." -ForegroundColor Yellow
        if ($DryRun) {
            Write-Host "  [DRY-RUN] nada enviado." -ForegroundColor Magenta
        } else {
            Invoke-Native -Exe $rclone -Arguments (@("copyto", $LOCAL_APK, "$historyDest/$historyName") + $s3Flags + $apkHeaders + @("--progress")) -What "rclone copyto (historico)"
            Write-Host "  OK" -ForegroundColor Green
        }
    }
} else {
    Print-Step "PASSO 5/10: Upload historico (SKIPPED)"
}

# =====================================================================
#  PASSO 6: UPLOAD - DISTRIBUICAO
# =====================================================================
Print-Step "PASSO 6/10: Upload distribuicao (URL publica)"

Write-Host "Enviando $APK_NAME -> $($cfg.bucket)/$R2_FOLDER ..." -ForegroundColor Yellow
if ($DryRun) {
    Write-Host "  [DRY-RUN] nada enviado." -ForegroundColor Magenta
} else {
    Invoke-Native -Exe $rclone -Arguments (@("copyto", $LOCAL_APK, "$distDest/$APK_NAME") + $s3Flags + $apkHeaders + @("--progress")) -What "rclone copyto (distribuicao)"
    Invoke-Native -Exe $rclone -Arguments (@("copyto", $sha256File, "$distDest/$APK_NAME.sha256") + $s3Flags + $txtHeaders) -What "rclone copyto (.sha256)"
    Write-Host "  OK" -ForegroundColor Green
}

# =====================================================================
#  PASSO 7: VERIFICACAO POS-UPLOAD (ORIGEM R2)
# =====================================================================
# Atencao: isto confere o objeto no R2 via API S3, que NAO passa pelo cache de
# borda. Passar aqui nao prova que o cliente recebe os bytes certos -- quem prova
# isso e o passo 10.
Print-Step "PASSO 7/10: Verificacao pos-upload (origem R2)"

if ($DryRun) {
    Write-Host "  [DRY-RUN] verificacao pulada." -ForegroundColor Magenta
} else {
    $remoteRaw = Invoke-NativeCapture -Exe $rclone -Arguments (@("lsjson", $distDest) + $s3Flags + @("--hash")) -What "rclone lsjson (distribuicao)"
    $remoteEntries = $remoteRaw | ConvertFrom-Json

    $live = $remoteEntries | Where-Object { -not $_.IsDir -and $_.Name -eq $APK_NAME } | Select-Object -First 1
    if (-not $live) { throw "VERIFICACAO FALHOU: $APK_NAME nao encontrado em $R2_FOLDER apos o upload." }

    if ($live.Size -ne $apkSize) {
        throw "VERIFICACAO FALHOU: tamanho diferente (local: $apkSize bytes, remoto: $($live.Size) bytes)"
    }
    Write-Host "  OK: tamanho confere ($apkSize bytes)" -ForegroundColor Green

    if ($live.Hashes -and $live.Hashes.md5) {
        if ($live.Hashes.md5.ToLower() -ne $apkMd5) {
            throw "VERIFICACAO FALHOU: MD5 diferente (local: $apkMd5, remoto: $($live.Hashes.md5.ToLower()))"
        }
        Write-Host "  OK: MD5 confere ($apkMd5)" -ForegroundColor Green
    } else {
        Write-Host "  AVISO: R2 nao retornou MD5 (upload multipart?) - so o tamanho foi conferido." -ForegroundColor Yellow
    }

    $liveSha = $remoteEntries | Where-Object { -not $_.IsDir -and $_.Name -eq "$APK_NAME.sha256" } | Select-Object -First 1
    if ($liveSha) {
        Write-Host "  OK: $APK_NAME.sha256 publicado" -ForegroundColor Green
    } else {
        Write-Host "  AVISO: $APK_NAME.sha256 nao encontrado no remoto." -ForegroundColor Yellow
    }
}

# =====================================================================
#  PASSO 8: ANUNCIO (version.json)
# =====================================================================
# Por ultimo entre os artefatos, de proposito: e o version.json que dispara a
# atualizacao nos apps instalados. Se subisse antes, um cliente poderia ver
# "versao nova disponivel" e baixar um APK que ainda nao esta no ar.
Print-Step "PASSO 8/10: Anuncio da versao (version.json)"

Write-Host "Destino: $PUBLIC_BASE/$R2_FOLDER/version.json" -ForegroundColor Yellow
Write-Host "Conteudo:" -ForegroundColor Yellow
Write-Host $versionPayload -ForegroundColor White
Write-Host ""

if ($DryRun) {
    Write-Host "  [DRY-RUN] nada enviado." -ForegroundColor Magenta
} else {
    # TTL curto: o app pede no-cache, mas isso limita quanto tempo um cache
    # intermediario pode segurar o anuncio da versao nova.
    $jsonHeaders = @(
        "--header-upload=Content-Type: application/json; charset=utf-8",
        "--header-upload=Cache-Control: max-age=300"
    )
    Invoke-Native -Exe $rclone -Arguments (@("copyto", $versionJsonFile, "$distDest/version.json") + $s3Flags + $jsonHeaders) -What "rclone copyto (version.json)"

    $jsonRaw = Invoke-NativeCapture -Exe $rclone -Arguments (@("lsjson", $distDest) + $s3Flags) -What "rclone lsjson (version.json)"
    $liveJson = ($jsonRaw | ConvertFrom-Json) | Where-Object { -not $_.IsDir -and $_.Name -eq "version.json" } | Select-Object -First 1
    if (-not $liveJson) { throw "VERIFICACAO FALHOU: version.json nao encontrado apos o upload." }
    Write-Host "  OK: version.json publicado ($($liveJson.Size) bytes)" -ForegroundColor Green
}

# =====================================================================
#  PASSO 9: PURGA DO CACHE DE BORDA
# =====================================================================
Print-Step "PASSO 9/10: Purga do cache de borda (Cloudflare)"

$cfToken = $Script:CF_API_TOKEN
$cfZone  = $Script:CF_ZONE_ID

if ($DryRun) {
    Write-Host "  [DRY-RUN] nada purgado." -ForegroundColor Magenta
} elseif ([string]::IsNullOrWhiteSpace($cfToken) -or [string]::IsNullOrWhiteSpace($cfZone)) {
    Write-Host "  PULADO: CF_API_TOKEN / CF_ZONE_ID nao definidos em build.properties." -ForegroundColor Yellow
    Write-Host "  O link de download manual pode servir a versao anterior ate o cache expirar." -ForegroundColor Yellow
    Write-Host "  Purgue no painel: Caching -> Configuracao -> Limpeza personalizada." -ForegroundColor Yellow
} else {
    # So as URLs do FORK. Purgar as da linha antiga seria mexer no cache deles.
    $purgeUrls = @(
        "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME",
        "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME.sha256",
        "$PUBLIC_BASE/$R2_FOLDER/version.json"
    )
    foreach ($u in $purgeUrls) { Write-Host "  purgando $u" -ForegroundColor Yellow }

    # A permissao correta e de ZONA: "Cache" -> acao "Purge". Um token com escopo
    # de conta inteira nem enxerga essa permissao e falha com erro 10000.
    $purgeResp = Invoke-RestMethod -Method Post `
        -Uri "https://api.cloudflare.com/client/v4/zones/$cfZone/purge_cache" `
        -Headers @{ Authorization = "Bearer $cfToken" } `
        -ContentType 'application/json' `
        -Body (@{ files = $purgeUrls } | ConvertTo-Json) `
        -TimeoutSec 60

    if (-not $purgeResp.success) {
        throw "Purga falhou: $($purgeResp.errors | ConvertTo-Json -Compress)"
    }
    Write-Host "  OK: cache purgado" -ForegroundColor Green
    Start-Sleep -Seconds 5   # propagacao pelos datacenters
}

# =====================================================================
#  PASSO 10: VERIFICACAO PUBLICA + PROVA DE NAO-INTERFERENCIA
# =====================================================================
Print-Step "PASSO 10/10: Verificacao publica e prova de nao-interferencia"

if ($DryRun) {
    Write-Host "  [DRY-RUN] verificacao pulada." -ForegroundColor Magenta
} else {
    $savedProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        $jsonUrl = "$PUBLIC_BASE/$R2_FOLDER/version.json"
        Write-Host "Lendo $jsonUrl ..." -ForegroundColor Yellow
        $publicJson = Invoke-RestMethod -Uri $jsonUrl -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 60

        if ([int]$publicJson.versionCode -ne [int]$VERSION_CODE) {
            throw "VERIFICACAO FALHOU: version.json publico anuncia versionCode $($publicJson.versionCode), esperado $VERSION_CODE"
        }
        if ($publicJson.sha256 -ne $apkSha256) {
            throw "VERIFICACAO FALHOU: version.json publico anuncia sha256 $($publicJson.sha256), esperado $apkSha256"
        }
        if ($publicJson.channel -ne $UPDATE_CHANNEL) {
            throw "VERIFICACAO FALHOU: canal publicado '$($publicJson.channel)', esperado '$UPDATE_CHANNEL'. O app recusaria esta atualizacao."
        }
        Write-Host "  OK: anuncio publico bate com o build ($($publicJson.versionName), code $($publicJson.versionCode), canal $($publicJson.channel))" -ForegroundColor Green

        # Baixa exatamente a URL que o app vai usar e confere byte a byte.
        $probe = Join-Path $env:TEMP "armsx2fork-publish-probe.apk"
        Write-Host "Baixando $($publicJson.apkUrl) ..." -ForegroundColor Yellow
        Invoke-WebRequest -Uri $publicJson.apkUrl -OutFile $probe -TimeoutSec 300 -Headers @{ 'Cache-Control' = 'no-cache' }
        $probeSha  = (Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLower()
        $probeSize = (Get-Item -LiteralPath $probe).Length
        Remove-Item $probe -Force -ErrorAction SilentlyContinue

        if ($probeSha -ne $apkSha256) {
            throw @"
VERIFICACAO FALHOU: a URL publica entregou bytes DIFERENTES do que foi publicado.
  esperado: $apkSha256 ($apkSize bytes)
  recebido: $probeSha ($probeSize bytes)
Normalmente e cache de borda servindo o APK anterior: purgue o cache do
Cloudflare para $PUBLIC_BASE/$R2_FOLDER/* e rode de novo.
"@
        }
        Write-Host "  OK: a URL do app entrega os bytes certos ($probeSize bytes)" -ForegroundColor Green

        # A URL sem query string e a que circula para download manual. Se estiver
        # velha nao quebra a atualizacao in-app, mas quem clicar baixa a anterior.
        $bareUrl = "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME"
        $bareOk = $false
        for ($try = 1; $try -le 4 -and -not $bareOk; $try++) {
            try {
                $bare = Join-Path $env:TEMP "armsx2fork-publish-bare.apk"
                Invoke-WebRequest -Uri $bareUrl -OutFile $bare -TimeoutSec 300
                $bareSha = (Get-FileHash -LiteralPath $bare -Algorithm SHA256).Hash.ToLower()
                Remove-Item $bare -Force -ErrorAction SilentlyContinue
                if ($bareSha -eq $apkSha256) { $bareOk = $true; break }
                if ($try -lt 4) {
                    Write-Host "  ... link manual ainda com a versao anterior, aguardando propagacao ($try/4)" -ForegroundColor DarkGray
                    Start-Sleep -Seconds 10
                }
            } catch {
                Write-Host "  AVISO: nao foi possivel conferir o link manual: $($_.Exception.Message)" -ForegroundColor Yellow
                break
            }
        }
        if ($bareOk) {
            Write-Host "  OK: link de download manual tambem ja serve a nova versao" -ForegroundColor Green
        } else {
            Write-Host "  AVISO: o link de download manual ainda serve a versao anterior." -ForegroundColor Yellow
            Write-Host "         $bareUrl" -ForegroundColor Yellow
            Write-Host "         A atualizacao in-app NAO e afetada (usa ?v=$VERSION_CODE)." -ForegroundColor Yellow
        }

        # --- prova de nao-interferencia -------------------------------
        # O requisito do produto e "esta app nao pode gerar atualizacao para quem
        # baixou a outra". Isto e o que mede o requisito, em vez de confiar nele.
        Write-Host ""
        Write-Host "Conferindo que a LINHA ANTIGA nao foi tocada..." -ForegroundColor Yellow
        if ($null -eq $legacyBefore) {
            Write-Host "  PULADO: a foto do passo 1 nao pode ser tirada." -ForegroundColor Yellow
        } else {
            $legacyAfter = Invoke-RestMethod -Uri $LEGACY_ENDPOINT -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 60
            if ([int]$legacyAfter.versionCode -ne [int]$legacyBefore.versionCode -or
                $legacyAfter.sha256 -ne $legacyBefore.sha256) {
                throw @"
A LINHA ANTIGA MUDOU DURANTE ESTA PUBLICACAO.
  antes:  $($legacyBefore.versionName) (code $($legacyBefore.versionCode)) sha $($legacyBefore.sha256)
  depois: $($legacyAfter.versionName) (code $($legacyAfter.versionCode)) sha $($legacyAfter.sha256)
Este script nao escreve em $LEGACY_FOLDER/. Ou alguem publicou pela linha antiga
ao mesmo tempo, ou um dos guards foi alterado. Investigue antes de seguir.
"@
            }
            Write-Host ("  OK: {0} continua em {1} (code {2}) -- os clientes dela nao receberam nada." -f `
                $LEGACY_FOLDER, $legacyAfter.versionName, $legacyAfter.versionCode) -ForegroundColor Green
        }
    } finally {
        $ProgressPreference = $savedProgress
    }
}

# =====================================================================
#  RESUMO
# =====================================================================
Print-Step "RESUMO" "Green"

Write-Host "Trilha:  FORK ($R2_FOLDER, canal $UPDATE_CHANNEL)" -ForegroundColor Green
Write-Host "Versao:  $VERSION_NAME (code $VERSION_CODE)" -ForegroundColor Green
Write-Host "SHA-256: $apkSha256" -ForegroundColor Green
Write-Host ""
Write-Host "Link para o cliente do FORK (o unico que deve circular para esta versao):" -ForegroundColor Cyan
Write-Host "  $PUBLIC_BASE/$R2_FOLDER/$APK_NAME" -ForegroundColor White
Write-Host ""
Write-Host "Anuncio lido pelo app (atualizacao in-app):" -ForegroundColor Cyan
Write-Host "  $PUBLIC_BASE/$R2_FOLDER/version.json" -ForegroundColor White
Write-Host ""
Write-Host "Hash publicado (suporte descartar download corrompido):" -ForegroundColor Cyan
Write-Host "  $PUBLIC_BASE/$R2_FOLDER/$APK_NAME.sha256" -ForegroundColor White

if (-not $SkipHistory) {
    Write-Host ""
    Write-Host "Historico (rollback):" -ForegroundColor Cyan
    Write-Host "  $PUBLIC_BASE/$R2_HISTORY/$historyName" -ForegroundColor White
    Write-Host ""
    Write-Host "Para reverter para esta versao no futuro:" -ForegroundColor Cyan
    Write-Host "  rclone copyto :s3:$($cfg.bucket)/$R2_HISTORY/$historyName :s3:$($cfg.bucket)/$R2_FOLDER/$APK_NAME <flags-s3>" -ForegroundColor White
}

Write-Host ""
Write-Host "A LINHA ANTIGA continua servindo os clientes dela em:" -ForegroundColor DarkGray
Write-Host "  $PUBLIC_BASE/$LEGACY_FOLDER/$APK_NAME" -ForegroundColor DarkGray
Write-Host "Nenhum aparelho daquela trilha recebe esta versao: o endpoint e compilado" -ForegroundColor DarkGray
Write-Host "dentro de cada APK, e o canal ('$UPDATE_CHANNEL' vs 'default') e a segunda tranca." -ForegroundColor DarkGray

Write-Host ""
if ($DryRun) {
    Write-Host "DRY-RUN concluido - nada foi publicado." -ForegroundColor Magenta
} else {
    Write-Host "Publicacao concluida com sucesso!" -ForegroundColor Green
}
