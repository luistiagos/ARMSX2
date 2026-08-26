<#
.SYNOPSIS
    Build release + publicacao do APK do RetroSystem PS2 no Cloudflare R2.

.DESCRIPTION
    Passo a passo completo:

    1. BUILD
       - Incrementa versionCode (+1) e o patch do versionName no
         app/build.gradle ANTES de compilar. Use -NoBump para nao mexer, ou
         -VersionName 1.1.0 para um salto que nao seja de patch.
       - Executa .\deploy_release.ps1 (assembleUnrestrictedRelease)
       - Gera dist\retrosystem-ps2.apk

    2. VERIFICACAO (gate - aborta a publicacao se falhar)
       - Confere que o APK esta assinado com a chave de release oficial
         (cert SHA-256 fixo abaixo). Protege contra o fallback silencioso do
         deploy_release.ps1, que assina com debug.keystore ou keystore gerado
         na hora quando app\armsx2_keystore.properties nao resolve.
         APK com chave errada publicado = todo usuario instalado quebra na
         atualizacao e so sai desinstalando (perde saves e memory cards).
       - Le versionCode/versionName do proprio APK (aapt), nao do build.gradle.

    3. UPLOAD PARA R2 - HISTORICO (versionado, nunca sobrescreve)
       - rgs/ps2/history/retrosystem-ps2-<versionName>-<versionCode>.apk
       - Se a versao ja existe no remoto com bytes diferentes, aborta (use -Force).
       - Da rollback: basta copiar um arquivo do history/ por cima do de distribuicao.

    >>> TRAVA: os passos 4 a 8 so rodam com -Announce. Sem ela o script para
        depois do historico -- constroi, confere a assinatura e arquiva, sem que
        nenhum usuario receba nada. Ver o comentario do parametro.

    4. UPLOAD PARA R2 - DISTRIBUICAO (nome estavel, sempre sobrescreve)
       - rgs/ps2/retrosystem-ps2.apk        <- URL publica que o cliente baixa
       - rgs/ps2/retrosystem-ps2.apk.sha256 <- suporte descarta corrupcao na hora

       Enviado por ultimo, de proposito: o arquivo ao vivo so muda depois que
       todo o resto passou.

    5. VERIFICACAO POS-UPLOAD
       - Confere tamanho E hash MD5 (ETag) de cada objeto remoto contra o local.

    6. ANUNCIO (rgs/ps2/version.json)
       - E o arquivo que o AppUpdateManager do app le para saber que saiu
         versao nova. Carrega versionCode/versionName/channel/apkUrl/sha256/size.
       - Enviado por ULTIMO: e ele que dispara a atualizacao nos apps
         instalados, entao so vai ao ar depois do APK verificado no passo 5.

    7. PURGA DO CACHE DE BORDA (Cloudflare)
       - Sem isto o link que circula com os clientes continua entregando o APK
         da versao anterior por tempo indeterminado.
       - Precisa de CF_API_TOKEN + CF_ZONE_ID em build.properties. A permissao
         e de ZONA: "Cache" -> acao "Purge". Token com escopo de conta inteira
         NAO enxerga essa permissao e falha com erro 10000.
       - Sem os dois valores o passo e pulado com aviso (nao falha).

    8. VERIFICACAO PELA URL PUBLICA
       - Baixa a URL que o app usa e confere o SHA-256. E o unico passo que
         prova o que o cliente recebe: o passo 5 fala com o R2 pela API S3 e
         pula o cache de borda, entao passar nele nao garante nada.
       - Confere tambem o link manual (sem ?v=), com retry para a propagacao.

    Bucket R2 "versions" e o mesmo do Lemuroid/GODSend, servido publicamente em
    https://versions.digitalstoregames.com - o ARMSX2 usa a pasta rgs/ps2/.

.PARAMETER SkipBuild
    Pula o build e usa o APK existente em dist\.

.PARAMETER SkipHistory
    Nao envia a copia versionada (so atualiza a de distribuicao).

.PARAMETER DryRun
    Faz todas as verificacoes e mostra o que seria enviado, sem escrever no R2.

.PARAMETER Force
    Permite sobrescrever uma versao ja existente no history/ com bytes diferentes.

.PARAMETER NoBump
    Nao incrementa a versao (usa a que estiver no app/build.gradle).

.PARAMETER VersionName
    versionName explicito para esta publicacao (ex: -VersionName 1.1.0).
    Sem isto, o patch e incrementado: 1.0.9 -> 1.0.10.

.EXAMPLE
    .\build-and-upload.ps1
    Build + verificacao + historico + distribuicao.

.EXAMPLE
    .\build-and-upload.ps1 -SkipBuild -DryRun
    Usa o APK em dist\ e mostra o que seria publicado, sem enviar nada.

.NOTES
    Pre-requisitos:
    - Mesmos do deploy_release.ps1 (gradlew + app\retrosystem_release.jks) quando nao usar -SkipBuild
    - rclone (winget install Rclone.Rclone)
    - Android SDK build-tools (apksigner + aapt), achado via ANDROID_HOME / ANDROID_SDK_ROOT
    - Credenciais R2: R2_* em build.properties, ou r2-config.json na raiz,
      ou fallback para E:\projects\GODSend\r2-config.json (mesma conta R2)
#>

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipHistory,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$NoBump,
    [string]$VersionName = "",

    # -Announce e o UNICO caminho para a atualizacao chegar ao usuario.
    #
    # Sem ela, o script para depois do historico: constroi, confere a assinatura e guarda o APK
    # versionado em rgs/ps2/history/, que nao esta linkado em lugar nenhum. NAO sobrescreve o APK
    # de distribuicao e NAO publica o version.json.
    #
    # Isto e default por decisao, nao por cautela generica: o fork sobre a arvore do upstream troca
    # o app inteiro do usuario, e a validacao pesada em aparelho ainda nao foi feita. Um -Announce
    # digitado por engano nao volta atras -- os apps instalados consultam o version.json sozinhos.
    [switch]$Announce
)

$ErrorActionPreference = "Stop"

# --- CONFIG ------------------------------------------
$PROJECT_ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$DIST_DIR     = Join-Path $PROJECT_ROOT "dist"
$APK_NAME     = "retrosystem-ps2.apk"
$LOCAL_APK    = Join-Path $DIST_DIR $APK_NAME
$ENV_FILE     = Join-Path $PROJECT_ROOT "build.properties"

# A estrutura mudou com o fork: o modulo Android vive em platforms/android/, a versao mora no
# gradle.properties (e nao no build.gradle) e o build e do flavor `github` -- o de sideload, o unico
# que carrega REQUEST_INSTALL_PACKAGES e o updater. O flavor `play` nunca passa por aqui.
$ANDROID_DIR  = Join-Path $PROJECT_ROOT "platforms\android"
$GRADLE_FILE  = Join-Path $ANDROID_DIR "gradle.properties"
$GRADLEW      = Join-Path $ANDROID_DIR "gradlew.bat"
$BUILT_APK    = Join-Path $ANDROID_DIR "app\build\outputs\apk\github\release\app-github-release.apk"

# Destino no bucket R2
$R2_FOLDER    = "rgs/ps2"
$R2_HISTORY   = "rgs/ps2/history"
$PUBLIC_BASE  = "https://versions.digitalstoregames.com"

# Canal de atualizacao. Precisa bater com BuildConfig.APP_UPDATE_CHANNEL
# (app/build.gradle) - o app recusa um version.json de canal diferente.
$UPDATE_CHANNEL = "default"

# Configuracao R2 (mesma cascata do Lemuroid)
$LOCAL_R2_CONFIG   = Join-Path $PROJECT_ROOT "r2-config.json"
$GODSEND_R2_CONFIG = "E:\projects\GODSend\r2-config.json"

# Chave de release oficial. NAO altere sem trocar o keystore de proposito -
# esse valor e o que impede um APK mal-assinado de chegar nos usuarios.
#   app\retrosystem_release.jks, alias "retrosystem"
#   CN=RetroSystem PS2, OU=Dev, O=Nanodata, L=Unknown, ST=Unknown, C=BR
$EXPECTED_CERT_SHA256 = "d34a788ab0f4fb5b467be5839c4317d66a46525397dfeebdeb40ba4b97c0745a"

# --- HELPERS -----------------------------------------
function Print-Step {
    param([string]$Message, [string]$Color = "Cyan")
    Write-Host ""
    Write-Host "========================================" -ForegroundColor $Color
    Write-Host "  $Message" -ForegroundColor $Color
    Write-Host "========================================" -ForegroundColor $Color
}

function Find-Rclone {
    $cmd = Get-Command rclone -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidate = Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter "rclone.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { return $candidate }

    throw "rclone.exe nao encontrado. Instale com: winget install Rclone.Rclone (e reabra o shell)."
}

# Resolve uma ferramenta do build-tools pegando sempre a versao mais recente instalada.
function Find-BuildTool {
    param([string]$ToolName)

    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
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

    throw "$ToolName nao encontrado no build-tools. Defina ANDROID_HOME apontando para o Android SDK."
}

# Sobe versionCode (+1) e o patch do versionName no app/build.gradle.
# TEM que rodar antes do gradle: se rodasse depois, o APK sairia com a versao
# antiga e o passo 2 (que le a versao do proprio APK) acusaria a divergencia.
#
# versionName sobe junto de proposito. Se so o code subisse, o dialogo no
# celular ficaria "A versao 1.0.9 esta disponivel. Voce tem a 1.0.9." Use
# -VersionName para saltos que nao sejam de patch (ex: 1.0.9 -> 1.1.0).
function Invoke-VersionBump {
    if ($NoBump) {
        Write-Host "Bump de versao: PULADO (-NoBump)" -ForegroundColor Yellow
        return
    }

    $raw = [System.IO.File]::ReadAllText($GRADLE_FILE)

    # \b evita casar com 'versionCodeOverride' do comentario logo acima, e a
    # primeira ocorrencia e a declaracao real (a de baixo e o archivesBaseName).
    # Sem ancora $ de proposito: com CRLF o $ multiline exige estar logo antes
    # do \n, mas [^\r\n]* nao consome o \r, e o casamento falharia sempre.
    # gradle.properties, nao build.gradle: as linhas sao `armsx2.versionCode=38`. Ancoradas no
    # inicio da linha para nao casar com nada dentro dos comentarios que explicam o porque.
    $codeLine = [regex]::Match($raw, '(?m)^armsx2\.versionCode[^\r\n]*').Value
    $nameLine = [regex]::Match($raw, '(?m)^armsx2\.versionName[^\r\n]*').Value
    if (-not $codeLine -or -not $nameLine) {
        throw "Nao encontrei versionCode/versionName em $GRADLE_FILE"
    }

    $codeNums = [regex]::Matches($codeLine, '\d+')
    if ($codeNums.Count -ne 1) {
        throw "A linha do versionCode tem $($codeNums.Count) numeros; nao da para incrementar com seguranca:`n  $codeLine"
    }
    $oldCode = [int]$codeNums[0].Value
    $newCode = $oldCode + 1

    # Em .properties o valor nao tem aspas: `armsx2.versionName=1.0.24`.
    $nameMatch = [regex]::Match($nameLine, '=\s*(.+?)\s*$')
    if (-not $nameMatch.Success) {
        throw "Nao encontrei o valor do versionName:`n  $nameLine"
    }
    $oldName = $nameMatch.Groups[1].Value

    if ($VersionName) {
        $newName = $VersionName
    } elseif ($oldName -match '^(\d+)\.(\d+)\.(\d+)$') {
        $newName = "$($matches[1]).$($matches[2]).$([int]$matches[3] + 1)"
    } else {
        throw "versionName '$oldName' nao segue X.Y.Z; passe -VersionName <valor> para definir a nova versao."
    }

    Write-Host "  versionCode: $oldCode -> $newCode" -ForegroundColor White
    Write-Host "  versionName: $oldName -> $newName" -ForegroundColor White

    if ($DryRun) {
        Write-Host "  [DRY-RUN] gradle.properties nao foi alterado." -ForegroundColor Magenta
        return
    }

    $newCodeLine = "armsx2.versionCode=$newCode"
    $newNameLine = "armsx2.versionName=$newName"
    # Replace no texto bruto (e nao linha a linha) preserva as quebras de
    # linha originais do arquivo - senao o diff viraria o arquivo inteiro.
    $updated = $raw.Replace($codeLine, $newCodeLine).Replace($nameLine, $newNameLine)
    [System.IO.File]::WriteAllText($GRADLE_FILE, $updated, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  gradle.properties atualizado" -ForegroundColor Green
}

function Get-R2Config {
    # 1) R2_* em build.properties  2) r2-config.json local  3) fallback GODSend
    if ($Script:R2_ACCESS_KEY_ID -and $Script:R2_SECRET_ACCESS_KEY -and $Script:R2_ENDPOINT -and $Script:R2_BUCKET) {
        return [PSCustomObject]@{
            accessKeyId     = $Script:R2_ACCESS_KEY_ID
            secretAccessKey = $Script:R2_SECRET_ACCESS_KEY
            endpoint        = $Script:R2_ENDPOINT
            bucket          = $Script:R2_BUCKET
            source          = "build.properties"
        }
    }
    if (Test-Path -LiteralPath $LOCAL_R2_CONFIG) {
        $c = Get-Content -LiteralPath $LOCAL_R2_CONFIG -Raw -Encoding UTF8 | ConvertFrom-Json
        $c | Add-Member -NotePropertyName source -NotePropertyValue $LOCAL_R2_CONFIG -Force
        return $c
    }
    if (Test-Path -LiteralPath $GODSEND_R2_CONFIG) {
        $c = Get-Content -LiteralPath $GODSEND_R2_CONFIG -Raw -Encoding UTF8 | ConvertFrom-Json
        $c | Add-Member -NotePropertyName source -NotePropertyValue $GODSEND_R2_CONFIG -Force
        return $c
    }
    throw "Credenciais R2 nao encontradas. Defina R2_* em build.properties, crie r2-config.json na raiz, ou garanta que $GODSEND_R2_CONFIG existe."
}

# Load build.properties (opcional - so precisa se nao quiser usar o r2-config.json)
if (Test-Path -LiteralPath $ENV_FILE) {
    Get-Content -LiteralPath $ENV_FILE -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)\s*$') {
            $k = $matches[1].Trim()
            $v = $matches[2].Trim().Trim('"', "'")
            Set-Variable -Name $k -Value $v -Scope Script
        }
    }
}

# --- STEP 1: BUILD ----------------------------------
if (-not $SkipBuild) {
    Print-Step "PASSO 1/8: Build release (flavor github)"
    Invoke-VersionBump

    # Sem flags de identidade: applicationId, versionCode e versionName sao defaults do
    # gradle.properties (TASK-0017). Passa-las aqui seria reintroduzir o modo de falha que aqueles
    # defaults existem para eliminar -- esquecer uma e publicar com o versionCode 1088 do upstream,
    # que o Android nunca mais deixa baixar.
    Push-Location $ANDROID_DIR
    try {
        & $GRADLEW ":app:assembleGithubRelease"
        if ($LASTEXITCODE -ne 0) { throw "gradlew assembleGithubRelease falhou (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath $BUILT_APK)) {
        throw "Build terminou mas o APK nao apareceu em:`n  $BUILT_APK"
    }
    New-Item -ItemType Directory -Force -Path $DIST_DIR | Out-Null
    Copy-Item -LiteralPath $BUILT_APK -Destination $LOCAL_APK -Force
    Write-Host "  APK copiado para $LOCAL_APK" -ForegroundColor Green
} else {
    Print-Step "PASSO 1/8: Build (SKIPPED - usando APK existente em dist\)"
    # Sem build nao ha bump: o APK em dist\ ja carrega a versao dele, e subir o
    # build.gradle aqui so criaria divergencia entre o repo e o que foi publicado.
    Write-Host "Bump de versao: PULADO (-SkipBuild)" -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $LOCAL_APK)) {
    throw "APK nao encontrado: $LOCAL_APK`nExecute sem -SkipBuild."
}

# --- STEP 2: VERIFICACAO DO APK (gate) --------------
Print-Step "PASSO 2/8: Verificacao do APK"

$apksigner = Find-BuildTool "apksigner.bat"
$aapt      = Find-BuildTool "aapt.exe"

Write-Host "Conferindo assinatura..." -ForegroundColor Yellow
$signerOutput = & $apksigner verify --print-certs $LOCAL_APK
if ($LASTEXITCODE -ne 0) {
    throw "APK INVALIDO: apksigner recusou $LOCAL_APK (exit code $LASTEXITCODE). Nada foi publicado."
}

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
    Write-Host "  O build.gradle.kts do upstream assina o release com a keystore de" -ForegroundColor Yellow
    Write-Host "  DEBUG quando platforms\android\armsx2_keystore.properties nao existe" -ForegroundColor Yellow
    Write-Host "  ou esta incompleto -- e NAO falha ao fazer isso. Confira se existem:" -ForegroundColor Yellow
    Write-Host "    platforms\android\armsx2_keystore.properties (4 chaves)" -ForegroundColor Yellow
    Write-Host "    platforms\android\retrosystem_release.jks" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Publicar este APK quebraria a atualizacao de TODOS os usuarios" -ForegroundColor Red
    Write-Host "  instalados (so sairiam desinstalando, perdendo saves)." -ForegroundColor Red
    throw "Verificacao de assinatura falhou. Nada foi publicado."
}
Write-Host "  OK: assinado com a chave de release oficial." -ForegroundColor Green

# Versao lida do proprio artefato - descreve o que esta sendo publicado de fato.
$badging = & $aapt dump badging $LOCAL_APK
if ($LASTEXITCODE -ne 0) {
    throw "aapt dump badging falhou em $LOCAL_APK"
}
$pkgLine = $badging | Select-String -Pattern "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'"
if (-not $pkgLine) {
    throw "Nao foi possivel ler package/versionCode/versionName do APK."
}
$PACKAGE_ID   = $pkgLine.Matches[0].Groups[1].Value
$VERSION_CODE = $pkgLine.Matches[0].Groups[2].Value
$VERSION_NAME = $pkgLine.Matches[0].Groups[3].Value

$apkItem   = Get-Item -LiteralPath $LOCAL_APK
$apkSize   = $apkItem.Length
$apkSha256 = (Get-FileHash -LiteralPath $LOCAL_APK -Algorithm SHA256).Hash.ToLower()
$apkMd5    = (Get-FileHash -LiteralPath $LOCAL_APK -Algorithm MD5).Hash.ToLower()

Write-Host "  Pacote:  $PACKAGE_ID" -ForegroundColor White
Write-Host "  Versao:  $VERSION_NAME (code $VERSION_CODE)" -ForegroundColor White
Write-Host "  Tamanho: $apkSize bytes ($([math]::Round($apkSize / 1MB, 2)) MB)" -ForegroundColor White
Write-Host "  SHA-256: $apkSha256" -ForegroundColor White

# Sidecar de hash: deixa o suporte descartar download corrompido em segundos.
$sha256File = Join-Path $DIST_DIR "$APK_NAME.sha256"
"$apkSha256  $APK_NAME" | Out-File -FilePath $sha256File -Encoding ascii -Force

# version.json: o que o AppUpdateManager do app le para saber que saiu versao
# nova. Gerado a partir do proprio APK, entao nunca anuncia uma versao que nao
# esta publicada. O sha256 aqui e o que permite o app recusar um download
# truncado antes de entregar o arquivo ao instalador.
#
# O "?v=<versionCode>" NAO e enfeite. O cache de borda na frente do R2 continua
# servindo os bytes antigos na URL canonica por tempo indeterminado depois do
# upload - e ignora Cache-Control/Pragma no-cache do cliente. Medido em
# 2026-08-10: version.json ja anunciava 1.0.9 enquanto
# GET /rgs/ps2/retrosystem-ps2.apk ainda devolvia o APK 1.0.8.
# A query string muda a chave de cache, entao cada release busca bytes frescos.
# Sem isso o app baixa a versao errada, o SHA-256 nao bate e a atualizacao
# entra em loop de erro ate o cache expirar sozinho.
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

# --- R2: config + flags -----------------------------
$cfg = Get-R2Config
foreach ($field in @('accessKeyId', 'secretAccessKey', 'endpoint', 'bucket')) {
    if (-not $cfg.$field) { throw "Config R2 faltando campo obrigatorio: $field (fonte: $($cfg.source))" }
}

$rclone   = Find-Rclone
# --log-level ERROR silencia o NOTICE de "config file not found" que o rclone
# manda pro stderr. No PowerShell 5.1, com ErrorActionPreference=Stop, qualquer
# stderr de executavel nativo vira NativeCommandError e derruba o script mesmo
# com exit code 0.
$s3Flags  = @(
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

Write-Host ""
Write-Host "R2: bucket '$($cfg.bucket)' (credenciais de $($cfg.source))" -ForegroundColor Yellow

# --- STEP 3: UPLOAD - HISTORICO ---------------------
if (-not $SkipHistory) {
    Print-Step "PASSO 3/8: Upload historico (versionado)"

    $existing = & $rclone lsjson $historyDest @s3Flags --hash | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "rclone lsjson falhou em $historyDest (exit code $LASTEXITCODE)" }

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
Isso significa que a versao $VERSION_NAME (code $VERSION_CODE) ja foi publicada com outro conteudo.
Suba o versionCode/versionName em app\build.gradle, ou use -Force se realmente quiser sobrescrever o historico.
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
            & $rclone copyto $LOCAL_APK "$historyDest/$historyName" @s3Flags @apkHeaders --progress
            if ($LASTEXITCODE -ne 0) { throw "rclone copyto falhou no historico (exit code $LASTEXITCODE)" }
            Write-Host "  OK" -ForegroundColor Green
        }
    }
} else {
    Print-Step "PASSO 3/8: Upload historico (SKIPPED)"
}


# --- TRAVA DE DIVULGACAO ----------------------------
# Daqui para baixo tudo CHEGA AO USUARIO: o passo 4 sobrescreve o APK que a URL publica entrega, e
# o passo 6 publica o version.json, que e o que faz os apps ja instalados oferecerem a atualizacao
# sozinhos. O passo 3 (historico) e o unico que nao chega: e versionado e nao esta linkado em
# lugar nenhum.
#
# Por isso a trava fica AQUI e nao no inicio: sem -Announce, o script ainda constroi, confere a
# assinatura contra a chave oficial e arquiva o APK no historico. O que ele nao faz e entregar.
if (-not $Announce) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Yellow
    Write-Host "  PARADO ANTES DE DIVULGAR (sem -Announce)" -ForegroundColor Yellow
    Write-Host "========================================" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Feito:" -ForegroundColor White
    Write-Host "    - build do flavor github" -ForegroundColor Gray
    Write-Host "    - assinatura conferida contra a chave oficial" -ForegroundColor Gray
    Write-Host "    - APK arquivado em $R2_HISTORY (nao linkado em lugar nenhum)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  NAO feito (e por isso nenhum usuario recebe nada):" -ForegroundColor White
    Write-Host "    - APK de distribuicao NAO foi sobrescrito" -ForegroundColor Gray
    Write-Host "    - version.json NAO foi publicado" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  Para instalar e testar este build a mao:" -ForegroundColor White
    Write-Host "    adb install -r `"$LOCAL_APK`"" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  Quando a validacao em aparelho estiver feita, repita com -Announce." -ForegroundColor White
    Write-Host "  Nao ha como desfazer: os apps instalados consultam o version.json sozinhos." -ForegroundColor Yellow
    Write-Host ""
    exit 0
}

# --- STEP 4: UPLOAD - DISTRIBUICAO ------------------
Print-Step "PASSO 4/8: Upload distribuicao (URL publica)"

Write-Host "Enviando $APK_NAME -> $($cfg.bucket)/$R2_FOLDER ..." -ForegroundColor Yellow
if ($DryRun) {
    Write-Host "  [DRY-RUN] nada enviado." -ForegroundColor Magenta
} else {
    & $rclone copyto $LOCAL_APK "$distDest/$APK_NAME" @s3Flags @apkHeaders --progress
    if ($LASTEXITCODE -ne 0) { throw "rclone copyto falhou na distribuicao (exit code $LASTEXITCODE)" }

    & $rclone copyto $sha256File "$distDest/$APK_NAME.sha256" @s3Flags @txtHeaders
    if ($LASTEXITCODE -ne 0) { throw "rclone copyto falhou no .sha256 (exit code $LASTEXITCODE)" }
    Write-Host "  OK" -ForegroundColor Green
}

# --- STEP 5: VERIFICACAO POS-UPLOAD (ORIGEM) --------
# Atencao: isto confere o objeto no R2 via API S3, que NAO passa pelo cache de
# borda. Passar aqui nao prova que o cliente recebe os bytes certos - quem prova
# isso e o PASSO 7, pela URL publica.
Print-Step "PASSO 5/8: Verificacao pos-upload (origem R2)"

if ($DryRun) {
    Write-Host "  [DRY-RUN] verificacao pulada." -ForegroundColor Magenta
} else {
    $remoteEntries = & $rclone lsjson $distDest @s3Flags --hash | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw "rclone lsjson falhou em $distDest (exit code $LASTEXITCODE)" }

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

# --- STEP 6: ANUNCIO (version.json) -----------------
# Por ultimo de proposito: e o version.json que dispara a atualizacao nos apps
# instalados. Se ele subisse antes, um cliente poderia ver "versao nova
# disponivel" e baixar um APK que ainda nao esta no ar (ou que falhou na
# verificacao do passo 5).
Print-Step "PASSO 6/8: Anuncio da versao (version.json)"

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
    & $rclone copyto $versionJsonFile "$distDest/version.json" @s3Flags @jsonHeaders
    if ($LASTEXITCODE -ne 0) { throw "rclone copyto falhou no version.json (exit code $LASTEXITCODE)" }

    $jsonEntries = & $rclone lsjson $distDest @s3Flags | ConvertFrom-Json
    $liveJson = $jsonEntries | Where-Object { -not $_.IsDir -and $_.Name -eq "version.json" } | Select-Object -First 1
    if (-not $liveJson) { throw "VERIFICACAO FALHOU: version.json nao encontrado apos o upload." }
    Write-Host "  OK: version.json publicado ($($liveJson.Size) bytes)" -ForegroundColor Green
}

# --- STEP 7: PURGA DO CACHE DE BORDA ----------------
# Sem isto, o link que circula com os clientes continua entregando o APK da
# versao anterior por tempo indeterminado. A atualizacao in-app nao depende
# disso (usa ?v=), mas quem clicar no link baixaria a versao velha.
Print-Step "PASSO 7/8: Purga do cache de borda (Cloudflare)"

$cfToken = $Script:CF_API_TOKEN
$cfZone  = $Script:CF_ZONE_ID

if ($DryRun) {
    Write-Host "  [DRY-RUN] nada purgado." -ForegroundColor Magenta
} elseif ([string]::IsNullOrWhiteSpace($cfToken) -or [string]::IsNullOrWhiteSpace($cfZone)) {
    Write-Host "  PULADO: CF_API_TOKEN / CF_ZONE_ID nao definidos em build.properties." -ForegroundColor Yellow
    Write-Host "  O link de download manual pode servir a versao anterior ate o cache expirar." -ForegroundColor Yellow
    Write-Host "  Purgue no painel: Caching -> Configuracao -> Limpeza personalizada." -ForegroundColor Yellow
} else {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $purgeUrls = @(
        "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME",
        "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME.sha256",
        "$PUBLIC_BASE/$R2_FOLDER/version.json"
    )
    foreach ($u in $purgeUrls) { Write-Host "  purgando $u" -ForegroundColor Yellow }

    $purgeResp = Invoke-RestMethod -Method Post `
        -Uri "https://api.cloudflare.com/client/v4/zones/$cfZone/purge_cache" `
        -Headers @{ Authorization = "Bearer $cfToken" } `
        -ContentType 'application/json' `
        -Body (@{ files = $purgeUrls } | ConvertTo-Json) `
        -TimeoutSec 60

    if (-not $purgeResp.success) {
        # A permissao correta e de ZONA: "Cache" -> acao "Purge". Um token com
        # escopo de conta inteira nem enxerga essa permissao e falha com 10000.
        throw "Purga falhou: $($purgeResp.errors | ConvertTo-Json -Compress)"
    }
    Write-Host "  OK: cache purgado" -ForegroundColor Green
    Start-Sleep -Seconds 5   # propagacao pelos datacenters
}

# --- STEP 8: VERIFICACAO PELA URL PUBLICA -----------
# O unico passo que prova o que o CLIENTE recebe. O passo 5 fala com o R2 pela
# API S3 e pula o cache de borda; ja aconteceu de o passo 5 passar enquanto a
# URL publica ainda servia o APK da versao anterior.
Print-Step "PASSO 8/8: Verificacao pela URL publica (o que o cliente recebe)"

if ($DryRun) {
    Write-Host "  [DRY-RUN] verificacao pulada." -ForegroundColor Magenta
} else {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
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
        Write-Host "  OK: anuncio publico bate com o build ($($publicJson.versionName), code $($publicJson.versionCode))" -ForegroundColor Green

        # Baixa exatamente a URL que o app vai usar e confere byte a byte.
        $probe = Join-Path $env:TEMP "armsx2-publish-probe.apk"
        Write-Host "Baixando $($publicJson.apkUrl) ..." -ForegroundColor Yellow
        Invoke-WebRequest -Uri $publicJson.apkUrl -OutFile $probe -TimeoutSec 300 -Headers @{ 'Cache-Control' = 'no-cache' }
        $probeSha = (Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLower()
        $probeSize = (Get-Item -LiteralPath $probe).Length
        Remove-Item $probe -Force -ErrorAction SilentlyContinue

        if ($probeSha -ne $apkSha256) {
            throw @"
VERIFICACAO FALHOU: a URL publica entregou bytes DIFERENTES do que foi publicado.
  esperado: $apkSha256 ($apkSize bytes)
  recebido: $probeSha ($probeSize bytes)
O app baixaria a versao errada, o SHA-256 nao bateria e a atualizacao entraria
em loop de erro. Normalmente e cache de borda servindo o APK anterior: purgue o
cache do Cloudflare para $PUBLIC_BASE/$R2_FOLDER/* e rode de novo.
"@
        }
        Write-Host "  OK: a URL do app entrega os bytes certos ($probeSize bytes)" -ForegroundColor Green

        # A URL sem query string e a que circula com os clientes para download
        # manual. Se estiver velha nao quebra a atualizacao in-app, mas quem
        # clicar no link baixa a versao anterior - vale purgar o cache.
        # Depois da purga do passo 7 isto deve passar. Tentamos algumas vezes
        # porque a purga leva alguns segundos para propagar pelos datacenters.
        $bareUrl = "$PUBLIC_BASE/$R2_FOLDER/$APK_NAME"
        $bareOk = $false
        for ($try = 1; $try -le 4 -and -not $bareOk; $try++) {
            try {
                $bare = Join-Path $env:TEMP "armsx2-publish-bare.apk"
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
            Write-Host "         Se persistir, purgue manualmente: Caching -> Configuracao -> Limpeza personalizada." -ForegroundColor Yellow
        }
    } finally {
        $ProgressPreference = $savedProgress
    }
}

# --- SUMMARY ----------------------------------------
Print-Step "RESUMO" "Green"

Write-Host "Versao publicada: $VERSION_NAME (code $VERSION_CODE)" -ForegroundColor Green
Write-Host "SHA-256: $apkSha256" -ForegroundColor Green
Write-Host ""
Write-Host "Link para o cliente (o unico que deve circular):" -ForegroundColor Cyan
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
if ($DryRun) {
    Write-Host "DRY-RUN concluido - nada foi publicado." -ForegroundColor Magenta
} else {
    Write-Host "Publicacao concluida com sucesso!" -ForegroundColor Green
}
