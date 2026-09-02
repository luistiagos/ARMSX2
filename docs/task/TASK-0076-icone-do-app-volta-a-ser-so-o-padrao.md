# TASK-0076: o ícone do app volta a ser só o padrão

- **Status:** concluída
- **Criada em:** 2026-09-02
- **Concluída em:** 2026-09-02
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Commit:** 8d0964b416, 695228cd38 (o vínculo é o prefixo `TASK-0076:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## A decisão, e de quem é

Do dono do produto, em 2026-09-02: *"Em ícone de aplicativo só deixe o padrão, desabilite a
funcionalidade que permite mudar."*

O app oferecia cinco ícones de launcher (Default, Classic, Gold, Retro, Minimal) num seletor em
Configurações → App. Passa a oferecer um: o que vem no APK.

## O que existe hoje

| peça | onde |
|---|---|
| seletor (FlowRow com os cinco) | `ui/settings/AppTab.kt:203` |
| troca via `PackageManager` | `utils/AppIconManager.kt` — `setAppIcon()` |
| entrada de busca `app.icon` | `ui/settingshub/SettingsSearchIndex.kt:25` |
| 4 `activity-alias` desligados no manifesto | `AndroidManifest.xml:112`–`170` |
| 8 mipmaps (`ic_launcher_<variante>[_round]`) | `res/mipmap-*` |

## Por que isto não é só apagar o seletor

`setAppIcon()` não escreve só uma preferência: ele chama `setComponentEnabledSetting()` **habilitando
o alias escolhido e DESABILITANDO o `BootSplashActivity` real**. Esse estado é do `PackageManager`,
por instalação, e **sobrevive à atualização do APK** — não é a nossa `SharedPreferences`, e apagar a
chave não o desfaz.

Daí as duas armadilhas:

1. **Só remover o seletor** deixaria quem escolheu "Gold" com o ícone dourado para sempre, sem
   nenhuma tela para voltar atrás.
2. **Remover os `activity-alias` do manifesto** é pior: nessas instalações o alias deixaria de
   existir e o `BootSplashActivity` continuaria DESABILITADO por decisão gravada → **nenhuma entrada
   no launcher**. O app fica inalcançável, e qualquer código de conserto nosso só rodaria se o
   usuário conseguisse abrir o app — que é justamente o que ele não consegue mais. Recuperação:
   desinstalar, perdendo os saves.

Por isso os aliases **ficam** no manifesto (todos `enabled="false"`, inertes) e o
`AppIconManager` deixa de ser um seletor e passa a ser a volta: no primeiro start depois da
atualização, devolve todos os componentes ao estado do manifesto.

O reset usa `COMPONENT_ENABLED_STATE_DEFAULT`, não `ENABLED`/`DISABLED`: `DEFAULT` **apaga** o
override gravado e devolve a decisão ao manifesto, em vez de gravar um segundo override por cima.
E começa pelo `BootSplashActivity`, antes dos aliases, para não existir instante algum sem entrada
no launcher.

## Escopo

**Entra:**

- `AppTab.kt` — fora o bloco do seletor (e os imports que ficam órfãos).
- `SettingsSearchIndex.kt` — fora a entrada `app.icon`, que apontaria para um bloco inexistente.
- `AppIconManager.kt` — reescrito: some o `enum AppIcon`, o `setAppIcon()`, o `currentIcon` e o
  `applyTaskDescription()` (este já era código morto — nenhum chamador). Fica
  `restoreDefaultIcon()`, uma vez por instalação, com guarda em preferência.
- `MainActivityRuntime.kt:2402` — `AppIconManager.load()` vira `restoreDefaultIcon()`.
- `I18n.kt` — fora as oito chaves `app.icon*`, sem uso depois disto.
- `assets/i18n/pt-BR.json` — as mesmas oito chaves traduzidas. É o único dos 19 JSONs de
  idioma que as tinha; `grep` no `.kt` não alcança `assets/`, e por isso elas quase ficaram
  para trás.
- `test/.../utils/AppIconTest.kt` — apagado. Testava só o `enum AppIcon` (o `fromId()` e as
  propriedades das cinco variantes); sem o enum ele não compila, e não há o que ele ainda
  cubra. `compileGithubDebugKotlin` **não** compila `src/test`, então isto não aparece na
  compilação do app — só em `:app:testGithubDebugUnitTest`.

**NÃO entra:**

- **Remover os `activity-alias` do manifesto.** É a armadilha 2 acima. Ficam como rede de segurança
  das instalações que já trocaram o ícone.
- **Apagar os mipmaps das variantes.** Ainda referenciados pelos aliases; apagá-los quebra o
  `AndroidManifest`.
- **Trocar o ícone padrão.** O `ic_launcher` é o mesmo de sempre.
- **Migrar `ARMSX2.xml`/`armsx2` além do necessário** — a chave `ui.app_icon` e a legada
  `app_icon_selection` são removidas no mesmo reset, e nada mais é tocado.

## Como validar

1. **Instalação limpa:** ícone padrão, e Configurações → App **não** tem mais o bloco "App Icon";
   a busca por "icon" não devolve nada.
2. **Instalação que tinha trocado:** com o APK anterior, escolher "Gold" (launcher mostra o
   dourado); atualizar por cima com este APK e abrir o app uma vez → o launcher volta ao ícone
   padrão. Conferir com
   `adb shell dumpsys package come.nanodata.armsx2 | grep -A5 "enabledComponents\|disabledComponents"`:
   nenhum componente sobra nas listas.
3. **Segundo start:** o reset não roda de novo (chave `ui.app_icon.reset_to_default` gravada) e o
   ícone continua o padrão.
4. O app continua abrindo pelo launcher em ambos os casos — que é o risco real desta mudança.
