# TASK-0074: a música do menu de pausa nasce desligada

- **Status:** em andamento
- **Criada em:** 2026-09-02
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0074:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## A decisão, e de quem é

Do dono do produto, em 2026-09-02: *"No menu também temos uma musiquinha, tire ela."*

Perguntei até onde ia o "tire ela" — apagar a feature (como a [TASK-0063](TASK-0063-fundo-da-biblioteca-para-de-animar.md)
fez com a onda animada) ou só desligar por padrão. A escolha foi **só desligar por padrão**: a
música some para todo mundo, e quem quiser continua tendo o botão em Configurações.

## Qual das duas músicas é

O app tem duas, e só uma toca:

| | default hoje | onde toca | asset |
|---|---|---|---|
| `LibraryMusic` | **false** — já silenciosa | biblioteca | `library_music.m4a`, 2,5 MB |
| `PauseMusic` | **true**, 45 % | **menu de pausa em jogo** | `pause_music.mp3`, 1,6 MB |

Conferido no aparelho antes de mexer: `ARMSX2.xml` não tem nenhuma das chaves
(`pauseMusic.enabled`, `ui.libraryMusic`), então o que vale nas instalações é o default do código.
A musiquinha ouvida é a `PauseMusic`.

## Por que virar o default alcança quem já tem o app instalado

`PauseMusic.EnabledKey` é gravado em **um só lugar**: `set()`, que é o toggle da tela de ajustes
(`PauseMusic.kt:98`). O `load()` apenas lê, com default. Ou seja:

- quem nunca tocou no botão — hoje, todo mundo — não tem a chave, e passa a pegar `false`;
- quem ligou de propósito tem a chave gravada `true`, e **continua com música**. É o comportamento
  correto para uma virada de default, e é por isso que este caso não precisa da migração one-shot
  que o `ConfigStore` usa para campos que ele persiste sempre (`KEY_OSD_OFF_MIGRATED` e irmãos).

## Uma contradição que estava no código

Os dois comentários sobre este default se contradiziam:

- `PauseMusic.kt:48` — *"On by default — the menu was silent and this fills it"*
- `AppTab.kt:871` — *"Off by default — audio starting when you open a menu is startling if you
  didn't ask for it"*

O código concordava com o primeiro. Esta task resolve a contradição para o lado que o `AppTab` já
documentava, e corrige o comentário do `PauseMusic` em vez de deixar os dois brigando.

## Escopo

**Entra:**

- `PauseMusic.kt` — `enabled` passa a `mutableStateOf(false)` e `load()` a
  `getBoolean(EnabledKey, false)`. São os dois pontos; deixar só um faz a UI e a reprodução
  discordarem no primeiro quadro.
- O comentário do `enabled`, que hoje diz o contrário do que o código passará a fazer.

**NÃO entra:**

- **Apagar a feature.** Foi a opção descartada explicitamente: o toggle, o slider de volume, a
  importação de faixa própria e o `pause_music.mp3` continuam todos onde estão.
- **`LibraryMusic`.** Já nasce desligada; não há nada a mudar.
- **Remover os assets do APK** (4,1 MB somados). Só faria sentido no caminho "apagar a feature".
- **`MenuSfx`** — os blips de navegação são outra coisa, seguem ligados.

## Como validar

1. Instalação limpa (ou qualquer aparelho que nunca tenha tocado no toggle): entrar num jogo, abrir
   o menu de pausa — **silêncio**.
2. Configurações → App: o toggle "In-Game Pause Music" aparece **desligado**, e ligá-lo faz a música
   começar ali mesmo (o `set()` já cobre o caso de o menu estar aberto).
3. Desligar e religar o app: fica ligada, porque agora a chave existe gravada.
4. `MenuSfx` (blips de navegação) inalterado.

## Resultado

Validado no SM-A127M (Galaxy A12s) em 2026-09-02, APK debug com a mudança.

**1. O menu de pausa está silencioso, no estado em que a música tocaria.** Com 007 - Agent Under
Fire rodando e o menu de pausa aberto (`overlayVisible == true`, que é metade do `pauseMenuUp` em
`MainActivityRuntime.kt:2768`), os players de áudio do app são só dois:

```
SoundPool  usage=USAGE_GAME  CONTENT_TYPE_SONIFICATION  state:idle   <- MenuSfx, intacto
AAudio     usage=USAGE_MEDIA                            state:started <- SPU2 do emulador
```

Nenhum `android.media.MediaPlayer` — que é o que `PauseMusic` e `LibraryMusic` usam — e **zero
linhas de log com a tag `PauseMusic`**. Também conferido antes disso na tela de ajustes em jogo
(`inGameScreen != null`, a outra metade do `pauseMenuUp`): idem.

**2. O toggle continua funcionando — só o default mudou.** Em Configurações → App:
"Música de pausa no jogo" aparece **desligada**, e "Biblioteca de música" segue desligada. Ligando
a de pausa: o switch acende, aparecem o slider "Pausar o volume da música" (45%), o texto da faixa
integrada e o botão de importar, e a chave é gravada (`pauseMusic.enabled=true`). Desligando de
novo, volta a `false`. A feature está inteira; mudou o ponto de partida.

**3. `MenuSfx` inalterado** — "Efeitos sonoros do menu" ligado, volume 15%.

### Um crash que apareceu no meio, e não é desta task

Ao fechar o jogo o processo morreu duas vezes (11:24:38 e 11:25:45), SIGABRT na thread `GS`:

```
GSRenderer.cpp:758: assertion failed in GSRenderer::BeginPresentFrame(bool):
"Host GPU lost too many times, device is probably completely wedged."
```

É device-lost da GPU Mali, na mesma família dos bugs de Mali já abertos
([tela vermelha](../bugs/open/gs-mali-tela-vermelha-e-page-fault-driver_2026-08-21T07-39.md),
[tela preta A07](../bugs/open/gs-tela-preta-silenciosa-sem-diagnostico-a07_2026-08-20T23-15.md)).
Esta task mexe em dois literais booleanos de `PauseMusic.kt` e não alcança o core; a verificação do
item 1 foi feita com o app vivo, entre os dois crashes. **Não investiguei** — fica dito para não
passar por achado desta task nem por regressão dela.
