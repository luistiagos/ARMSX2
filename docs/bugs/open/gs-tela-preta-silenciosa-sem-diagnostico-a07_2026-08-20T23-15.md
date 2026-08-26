# Bug: tela preta no Samsung A07 — falha silenciosa, sem nenhum log para diagnosticar

- **Detectado em:** 2026-08-20 23:15 (relato de usuário, reincidente)
- **Origem:** relato com screenshot (overlay de toque visível sobre área de jogo 100% preta)
- **Errors (serviço):** nenhum — não é crash, não gera telemetria
- **Classe:** fail
- **Reincidência:** já reportado antes e dado como corrigido em 1.0.17; **continua**
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0003](../../task/TASK-0003-bloco-b1-shader-cache-driver.md), [TASK-0004](../../task/TASK-0004-bloco-b2-log-boot-gs.md)

## Sintoma

Samsung Galaxy A07: o jogo abre, o overlay de gamepad aparece normalmente, e a área de render
fica totalmente preta. Nenhuma mensagem de erro, nenhum toast, nenhum diálogo.

## Por que continua "corrigido mas acontecendo"

### 1. Nenhuma falha do emulador é visível para o usuário

`Host::ReportErrorAsync` no Android **só escreve no log**, não mostra nada na tela
([main.cpp:1990-1996](../../../app/src/main/cpp/main.cpp#L1990-L1996)):

```cpp
void Host::ReportErrorAsync(const std::string_view title, const std::string_view message)
{
    if (!title.empty() && !message.empty())
        ERROR_LOG("ReportErrorAsync: {}: {}", title, message);
    ...
}
```

É por esse caminho que passa o erro `"Failed to create render device..."`
([GS.cpp:374-378](../../../app/src/main/cpp/pcsx2/GS/GS.cpp#L374-L378)), e é por ele que passa
qualquer falha de boot da VM. Resultado: **toda falha vira exatamente esta tela preta**, sem
distinguir BIOS ausente, ROM inválida, falha de device gráfico ou VM que não subiu.

### 2. E o log também não existe

O `ERROR_LOG` acima nunca chega ao logcat numa instalação padrão. A cadeia:

| Sink | Estado numa instalação padrão |
|---|---|
| `s_console_level` | `NONE` — perfil força `EnableSystemConsole=false` ([main.cpp:246](../../../app/src/main/cpp/main.cpp#L246)) |
| `s_file_level` | `NONE` — perfil força `EnableFileLogging=false` ([main.cpp:247](../../../app/src/main/cpp/main.cpp#L247)) |
| `s_android_file_level` | `NONE` — **hard-coded**, ver abaixo |
| `s_host_level` | `NONE` — `Log::SetHostOutputLevel` ([Console.cpp:446](../../../app/src/main/cpp/common/Console.cpp#L446)) **nunca é chamado em lugar nenhum do projeto** |

Todos os sinks nascem em `LOGLEVEL_NONE` ([Console.cpp:56-78](../../../app/src/main/cpp/common/Console.cpp#L56-L78))
e `s_max_level` é o `max` deles ([Console.cpp:471](../../../app/src/main/cpp/common/Console.cpp#L471)).
Com todos em `NONE`, `Log::GetMaxLevel()` devolve `NONE` e **as macros de log descartam a
mensagem antes de chegar no `__android_log_print`**. O `adb logcat` fica limpo.

### 3. O toggle "gravar logs" não liga o log nativo

[VMManager.cpp:526-530](../../../app/src/main/cpp/pcsx2/VMManager.cpp#L526-L530) lê a preferência,
descarta com `(void)` e força `NONE`:

```cpp
const bool record_android_logs = si.GetBoolValue("Logging", "RecordAndroidLog", false);
(void)record_android_logs;
Log::SetAndroidFileOutputLevel(LOGLEVEL_NONE, std::string());
```

O switch em [SettingsActivity.java:821-834](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L821-L834)
ainda liga o `LogcatRecorder` do lado Java, mas o logcat não tem o que gravar (item 2).

**Consequência combinada:** não existe hoje nenhum caminho pelo qual um usuário de campo produza
evidência do que falhou. Cada rodada de correção é um chute, e a validação é "o usuário disse que
continua preto".

## O que já foi tentado (e o estado real)

A 1.0.17 (versionCode 31) mudou o allowlist de Vulkan para mandar **Mali-G / Immortalis em
Android 12+ para Vulkan** ([GSDeviceVK.cpp:2060-2096](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2060-L2096)),
com o comentário *"avoiding broken OpenGL ES drivers on modern Android"*.

Verificado que **essa mudança está de fato no APK publicado** — o `libemucore.so` dentro de
`dist/retrosystem-ps2.apk` é o build Release de 2026-08-20 19:29, e o disassembly de
`GSDeviceVK::IsSuitableDefaultRenderer` mostra as comparações `"Mali"`/`"-G"`/`"Immortalis"`
montadas como imediatos e a segunda leitura de API level. *(Buscar o literal com `grep` no `.so`
não funciona: o clang converte esses literais curtos em imediatos e o linker descarta a string
de `.rodata`. O teste válido é o disassembly.)*

Ou seja: a correção foi entregue e **não resolveu**.

E ela contradiz a análise já registrada em
[gsdevicevk-allowlist-vulkan-rejeita-mali-moderno](../done/gsdevicevk-allowlist-vulkan-rejeita-mali-moderno_2026-08-10T16-02.md),
que concluiu o oposto: Mali não expõe `VK_EXT_rasterization_order_attachment_access`, o blending
do GS depende de framebuffer fetch, e em Mali o caminho com blending correto é o **OpenGL**
(`GL_ARM_shader_framebuffer_fetch`). O upstream tem relatos de campo de Mali+Vulkan quebrando
render ([#513](https://github.com/ARMSX2/ARMSX2/issues/513), [#232](https://github.com/ARMSX2/ARMSX2/issues/232)).

O fallback automático para OpenGL ([GS.cpp:356-369](../../../app/src/main/cpp/pcsx2/GS/GS.cpp#L356-L369))
**não cobre este caso**: ele só dispara se `GSDeviceVK::Create()` falhar. Um driver que cria o
device com sucesso e depois renderiza preto passa direto.

## Causa raiz

Duas, em camadas:

1. **Raiz do "não conseguimos corrigir":** falha silenciosa + log desligado por padrão + toggle de
   log morto. Sem isso, a causa da tela preta não é observável.
2. **Suspeita principal da tela preta em si (não confirmada, falta log):** o A07 (Helio G99 /
   Mali-G57, Android 15) passou a rodar Vulkan por causa da 1.0.17. Confirmar qual GPU e qual
   renderer o aparelho está usando é exatamente o que o log resolveria.

## Como reproduzir

Instalar a 1.0.17 num aparelho Mali com Android 12+, abrir qualquer jogo. Não há como observar a
causa hoje — `adb logcat -s NDK_LOG` volta vazio.

## Correções aplicadas (2026-08-20, ainda não publicadas)

1. **Falha do emulador agora aparece na tela.** `Host::ReportErrorAsync` passou a escrever direto no
   logcat (`__android_log_print`, fora do gate de `GetMaxLevel()`) e a chamar
   `NativeApp.onEmulatorError` via JNI, que abre um `MaterialAlertDialogBuilder` em
   `MainActivity.showEmulatorError`. Segue o mesmo padrão de `onPadVibration`; `SDL_GetAndroidJNIEnv`
   faz `AttachCurrentThread`, então é seguro chamar da thread de emulação. `minifyEnabled` é `false`
   nos dois build types, logo não precisa de regra `-keep`.
2. **`RecordAndroidLog` deixou de ser código morto** — [VMManager.cpp](../../../app/src/main/cpp/pcsx2/VMManager.cpp)
   agora honra o toggle e chama `SetAndroidFileOutputLevel(level, <Logs>/androidlog.txt)`. Isso sobe
   `s_max_level` e devolve **tanto o arquivo quanto o logcat**.
3. **Default de Mali revertido para OpenGL** — o bloco Mali-G/Immortalis saiu de
   `IsAllowlistedAndroidVulkanGPU`, alinhando com a análise de 2026-08-10. Confirmado no binário
   linkado: sobrou uma única chamada a `__system_property_get` (era duas) e nenhum imediato de
   `"Mali"`/`"-G"`/`"Immortalis"`.

4. **Safe mode do renderer automático** (2026-08-21, após relato de crash em Motorola com a 1.0.17).
   Relato: Vulkan crashava ao abrir o jogo, OpenGL manual funcionava. Investigando por que nenhum
   dos mecanismos existentes pegou:

   | Mecanismo | Por que não pegou |
   |---|---|
   | Allowlist ([GSDeviceVK.cpp](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2071)) | Compara **nome** de GPU. Não consulta extensão nem testa driver — só sabe o que alguém digitou nele. |
   | Fallback do `GSopen` ([GS.cpp:356-369](../../../app/src/main/cpp/pcsx2/GS/GS.cpp#L356-L369)) | Só dispara com `OpenGSDevice()` retornando `false`. A extensão que falta em Mali é **opcional** ([GSDeviceVK.cpp:445-447](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L445-L447)), então o device é criado com sucesso. E crash não é valor de retorno. |
   | `CrashReporter` ([App.java:55-58](../../../app/src/main/java/kr/co/iefriends/pcsx2/App.java#L55-L58)) | Telemetria pura. `reportNativeExitsAsync()` lê `ApplicationExitInfo` e **envia**; nada lê esse estado para mudar configuração. |

   Implementado em [GSUtil.cpp](../../../app/src/main/cpp/pcsx2/GS/GSUtil.cpp): dois arquivos em
   `EmuFolders::Cache` (`Settings` nunca é atribuído no Android — `SetDataDirectory()` não é chamado
   em lugar nenhum). `auto_renderer_boot.tmp` é armado antes do renderer automático ser usado e
   aposentado depois de 600 frames apresentados ([main.cpp `BeginPresentFrame`](../../../app/src/main/cpp/main.cpp#L1559))
   ou num shutdown limpo (`Host::OnVMDestroyed`). Sobreviver até o próximo arranque significa que a
   sessão anterior morreu sem apresentar um frame → grava `auto_renderer_no_vulkan.tmp`, cai para
   OpenGL e avisa **uma vez**. O bloqueio é persistente de propósito: sem isso o usuário alternaria
   entre sessão boa e crash para sempre. Escolher renderer na mão (spinner das Settings ou toggle do
   drawer) limpa os dois arquivos.

   **Limite deliberado:** só cobre o renderer *automático*. Vulkan escolhido explicitamente pelo
   usuário não é revertido — a saída é trocar nas Configurações sem abrir um jogo.

As mudanças foram publicadas em 2026-08-22 na versão 1.0.20 (`versionCode` 34). O bug permanece
aberto até o reteste em aparelho real e a confirmação pela telemetria.

## Próximos passos

1. **Publicar e pedir um log do A07** com o toggle de gravação ligado. Interessa:
   `"Using Vulkan GPU '...'"`, `"not allowlisted"`, `"Automatic renderer failed"`,
   `"Failed to create GS device"`, `"Unable to auto locate a BIOS image"`.
2. **Confirmar se a tela preta some com o renderer de volta em OpenGL.** Se não sumir, o renderer
   nunca foi a causa — e aí o diálogo de erro dirá o que é.
3. **Pendência lateral (BIOS), não corrigida:** [MainActivity.java:2232](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/MainActivity.java#L2232)
   ainda grava o **caminho absoluto** em `Filenames/BIOS` e não chama `refreshBIOS()`, enquanto os
   outros dois pontos de escrita já foram migrados para `getName()`. `Path::Combine`
   ([FileSystem.cpp:847-862](../../../app/src/main/cpp/common/FileSystem.cpp#L847-L862)) não trata
   caminho absoluto no segundo argumento — concatena e produz caminho inválido. Hoje isso é
   mascarado pelo fallback `FindBiosImage()` ([BiosTools.cpp:254-278](../../../app/src/main/cpp/pcsx2/ps2/BiosTools.cpp#L254-L278)),
   mas é inconsistente e deve ser fechado.

## Atualização — 2026-08-22: tela vermelha no A15 não é a mesma falha

Após a correção da cópia assíncrona de assets, um cliente com Galaxy A15 passou do sintoma preto
para uma área de jogo totalmente vermelha em Metal Gear Solid 3. O problema permanece após seleção
manual de Vulkan e não ocorre no Motorola G86.

Isso invalida “voltar Mali para OpenGL” como solução suficiente, mas não invalida o diagnóstico
deste documento: a tela preta anterior era um sintoma genérico de boot/render sem diagnóstico e a
race de assets era real. A tela vermelha é corrupção com frames sendo apresentados e, por isso,
não aciona o safe mode baseado em ausência de frames.

A telemetria aberta não contém o evento exato do A15/MGS3. Há, porém, um page fault decodificado
no driver Mali de um Galaxy A17 (error 1607), documentado separadamente em
[`gs-mali-tela-vermelha-e-page-fault-driver`](./gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md).
Ele é evidência do mesmo subsistema, não confirmação do mesmo sintoma.

## Recorrência — 2026-08-22: regressão confirmada no SM-A075M e fechamento do ponto cego preto

A varredura integral da telemetria (1.762 erros, 799 abertos) identificou o aparelho exato como
**Samsung SM-A075M / Android 16**. Os eventos `1510`, `1514`, `1574`, `1577`, `1745`, `1765`, `1766`
e `1768` são ANRs da versão 1.0.16 em `DataDirectoryManager.getDefaultDataRoot()`/`copyAssetAll()`.
Os eventos `1549`–`1563`, `1567`–`1569`, `1570`, `1578`, `1581`–`1584`, `1618` e `1619` registram
saídas/crashes nativos do mesmo aparelho nas versões 1.0.16/1.0.17; os tombstones antigos perderam
o backtrace porque foram truncados antes da correção do decoder.

O log anexado ao erro `1766` mostra o processo de emulação procurando as camadas Vulkan no momento
em que o jogo é aberto. Isso confirma que pelo menos as sessões problemáticas do A07 estavam de fato
no caminho Vulkan/Mali; não era apenas uma hipótese derivada do modelo.

Há ainda uma divergência de distribuição: em 2026-08-22 o APK público e seu `version.json` são
1.0.19/versionCode 33, com SHA-256
`e93906a8695713ba529e120da9eca1f0c6245fb8c81a2c3d7f20226a8e1736bc`, mas todos os eventos mais
recentes desse A07 continuam declarando 1.0.16. Portanto, o relato de teste na “versão mais recente”
não corresponde ao binário efetivamente executado pelo aparelho.

### Causas confirmadas no código

1. A preparação de recursos não era transacional por versão. A cópia antiga atualizava sempre os
   shaders, nunca atualizava os demais recursos já existentes e uma recriação da Activity podia iniciar
   cópias concorrentes. O boot esperava só 30 segundos e ignorava timeout/falha, permitindo que um core
   novo compilasse um conjunto incompleto de shaders em armazenamento lento.
2. `GraphicsHealthMonitor` tratava todo frame que não fosse vermelho como saudável. Assim, preto
   uniforme validava incorretamente o modo seguro; além disso, nenhuma tela preta persistente iniciava
   fallback ou gerava telemetria.
3. O marcador nativo baseado em `BeginPresentFrame()` também não prova saúde visual: um driver pode
   apresentar centenas de buffers integralmente pretos/vermelhos.

### Correção aplicada nesta rodada

- `DataDirectoryManager` agora instala todos os recursos empacotados uma vez por `versionCode`, grava
  o marcador somente depois de validar `GameIndex.yaml` e os shaders essenciais OpenGL/Vulkan, impede
  cópias sobrepostas por raiz de dados e devolve o sucesso real ao boot.
- `MainActivity` espera até 120 segundos fora da UI e **não inicia a VM** se a geração de recursos não
  estiver completa. Em vez da tela preta, volta à biblioteca, informa o usuário e envia
  `armsx2/assets` à telemetria.
- O monitor visual agora classifica vermelho uniforme, preto uniforme e frame saudável separadamente.
  Preto exige oito amostras consecutivas (~29 segundos desde o início do monitor), evitando fades e
  carregamentos comuns. Vulkan preto tenta OpenGL; OpenGL/modo seguro ainda inválido tenta Software.
  Cada resultado só é persistido para fingerprint + hardware + serial/CRC + renderer original.
- Um frame preto depois de vermelho não pode mais ser aceito como validação saudável.

### Validação local

`testUnrestrictedDebugUnitTest` e `assembleUnrestrictedDebug` concluíram com sucesso. Foram adicionados
testes para preto uniforme, vermelho uniforme, cena escura com detalhe visível e frame normal.

### Publicação para reteste

A versão **1.0.20 (`versionCode` 34)** foi publicada em 2026-08-22 pelo fluxo oficial, assinada
com o certificado de produção e verificada novamente pela URL pública. O APK tem 32.136.973 bytes
e SHA-256 `9f39e13ac5972d27430933a4866ac3f9382e67887abb33eb0847bba963bb8624`.

O rodapé da tela inicial agora mostra dinamicamente `Versão 1.0.20` usando o
`BuildConfig.VERSION_NAME`. Isso permite ao suporte confirmar por uma captura de tela qual binário
está realmente em execução, evitando repetir a divergência observada no A07 que ainda reportava
1.0.16 quando a distribuição já estava em 1.0.19.

Próxima evidência necessária: captura do rodapé e reteste de um jogo nos Galaxy A07/A15. Se o
quadro continuar preto, aguardar o monitor visual tentar Vulkan → OpenGL → Software e verificar os
novos eventos `armsx2/graphics-health`; se a preparação dos recursos falhar, verificar
`armsx2/assets`.
