# TASK-0036: música de fundo da biblioteca desligada por padrão

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0036:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário: retirar a música de fundo que toca ao abrir o app.

## O que existe

`LibraryMusic.kt` já é uma feature completa: toca só na biblioteca (para quando um jogo inicia),
tem volume ajustável, permite importar uma faixa própria e trata foco de áudio (chamada telefônica,
outro app tocando). A tela de Configurações (`AppTab.kt`) já expõe um toggle — "a chave de
desligar de verdade", segundo o próprio comentário do código.

## Por que ajustar o padrão, e não apagar a feature

O pedido descreve o sintoma — música tocando sem ter sido pedida — não uma rejeição da feature em
si: existe import de faixa própria, slider de volume, créditos na tela Sobre. Apagar tudo isso
jogaria fora trabalho funcional para resolver "ela toca sozinha ao abrir o app", quando a causa
raiz é só o valor padrão: `enabled = mutableStateOf(true)` e `load()` caindo em
`getBoolean(EnabledKey, true)` quando a chave nunca foi escrita.

Como `set()` só grava a chave `ui.libraryMusic` quando o usuário mexe no toggle, quem nunca tocou
nele — o caso comum, e o do usuário que pediu isto — não tem a chave no SharedPrefs. Mudar o
default resolve o sintoma relatado tanto para instalações novas quanto para quem já tem o app e
nunca abriu essa configuração, sem apagar o botão de quem quiser ligar de volta.

## O que mudou

Em `platforms/android/app/src/main/java/com/armsx2/LibraryMusic.kt`, duas linhas:

```kotlin
val enabled = mutableStateOf(false)                                    // era true
...
enabled.value = MainActivityRuntime.prefs.getBoolean(EnabledKey, false) // era true
```

Nada mais mudou: o toggle em Configurações → App continua lá, ligado por quem quiser.

## Como validar

`./gradlew :app:compileGithubDebugKotlin` — `BUILD SUCCESSFUL`. No aparelho: abrir o app não toca
mais nada; Configurações → App → Música da biblioteca aparece desligada, e ligá-la volta a tocar
normalmente (volume, faixa própria, tudo intacto).

## Resultado

Entregue. App abre em silêncio; quem quiser a música liga no mesmo toggle de sempre.
