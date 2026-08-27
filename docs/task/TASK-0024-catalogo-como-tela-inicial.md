# TASK-0024: abrir o app no catálogo e dar controle sobre o download em andamento

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0024:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Feedback do usuário olhando o app rodando, logo depois da TASK-0023: *"a grid só está com o único
jogo baixado, onde deveria aparecer a grid com todos do catálogo e um indicativo de qual já foi
baixado, da mesma forma que era o antigo"*.

## A grade inicial: o antigo abria no catálogo

Não era preferência, era o comportamento do app anterior — e dá para provar pelo código dele:

```xml
<!-- res/menu/menu_home_bottom_nav.xml (app anterior) -->
<item android:id="@+id/nav_catalog" ... />   <!-- primeiro -->
<item android:id="@+id/nav_saved"   ... />
```

`BottomNavigationView` seleciona o **primeiro** item por padrão, e `HomeActivity` só chamava
`setSelectedItemId(R.id.nav_saved)` depois de um jogo ter rodado. Ou seja: a primeira grade que o
usuário via era a dos 12.628 jogos, com os baixados marcados; a biblioteca era a segunda aba.

No fork o papel das duas abas fica com `AppRoute.Catalog` (agora a rota inicial) e `AppRoute.Home`
(a biblioteca, destino do "voltar" do catálogo). **Uma linha** — e por isso foi este o caminho.

A alternativa era fundir o catálogo dentro da grade da biblioteca, sintetizando entradas para o que
não está baixado. Isso mexeria em `HomeScreen`, `HomeViewModel` e `GameInfo` — o núcleo do upstream,
justamente onde o delta do fork precisa ser pequeno para os merges futuros continuarem baratos.

### A barra de cima teve que mudar junto

Como tela inicial, o catálogo abria com um `‹` que não tinha para onde voltar — e sem a engrenagem,
que a TASK-0022 estabeleceu como a única porta de configuração. Agora tem a gaveta (`☰`, onde mora
Configurações) à esquerda e a biblioteca (`▤`) à direita.

## O toast em inglês que apareceu junto

Ao abrir, o app avisava: *"Couldn't open your saved game folder — this can happen after
reinstalling…"*. Duas coisas erradas de uma vez, e as duas foram corrigidas:

**O diagnóstico estava errado.** `romsAccessible()` documenta, com razão, que em Android R+ um
caminho `/storage` só é de fato legível com `MANAGE_EXTERNAL_STORAGE`, e que `File.canRead()`
falso-positiva ali. Mas esse raciocínio vale para caminho **arbitrário**. A pasta semeada por
`seedOwnRomsFolder()` é o diretório de arquivos externos do próprio pacote: legível sempre, sem
permissão nenhuma. O app estava acusando de inacessível uma pasta que ele acabara de criar e estava
lendo. Ganhou uma exceção explícita, com caminho canônico dos dois lados e separador na comparação —
prefixo cru faria `.../files` casar com `.../files2`.

**E o texto era literal, em inglês, dentro do runtime.** Virou `setup.recovery.notice`, com as duas
línguas. Hasteado para fora do `LaunchedEffect`, porque `str` é `@Composable`.

## Cancelar um download: o buraco que a TASK-0023 deixou

A TASK-0023 registrou "pausar/retomar pela UI não exercitado". Ao ir exercitar, apareceu coisa pior:
**não havia como cancelar**. Um toque errado num jogo de 10 GB era irreversível — dava para pausar,
nunca para desistir. A versão anterior tinha (`dialog_rom_download`: um botão primário que mudava de
rótulo e um de cancelar).

O toque num cartão agora abre um painel único que conhece os estados:

| Estado do item | O que o painel oferece |
|---|---|
| nunca baixado | **Baixar** · Cancelar |
| na fila / baixando | **Pausar** · Cancelar download · Cancelar |
| pausado | **Retomar** · Cancelar download · Cancelar |
| já baixado | Fechar |

"Cancelar download" chama `DownloadQueueManager.remove()`, que para a transferência, tira da fila e
**apaga o `.part`** — as três coisas.

No view model, `onCardAction` (que adivinhava a intenção pelo estado) deu lugar a `start` / `pause` /
`resume` / `cancel`. Quem sabe o que o usuário escolheu é a tela: um botão escrito "Cancelar
download" não pode depender de o estado ainda ser o mesmo de quando foi desenhado.

`PadModal` e não `AlertDialog`, pelo mesmo motivo da TASK-0023 — e de novo com a trava do build
(`checkNoWindowModals`) como rede.

## Como validar

Ciclo completo no Galaxy A12 (SM-A127M, Android 13), lendo a árvore de UI a cada passo:

| Passo | Resultado |
|---|---|
| Abrir o app | catálogo, 12.628 jogos, `Baixado` no que já está e `Toque para baixar` no resto |
| Sem toast | o aviso em inglês não aparece mais |
| Tocar um cartão novo | painel `Baixar` · `Cancelar` |
| Baixar → tocar de novo | painel `0%` · `Pausar` · `Cancelar download` |
| Pausar | cartão em "Pausado — toque para retomar"; `.part` parado em 16.403.146 bytes |
| Retomar | painel `Retomar`; volta a andar (1%) |
| Cancelar download | cartão volta a "Toque para baixar"; `.part` **apagado**, só o jogo concluído sobra |

Fecha o "não exercitado" que a TASK-0023 deixou aberto.

## Resultado

Entregue. O padrão da TASK-0023 se repetiu: o toast em inglês e a ausência de cancelamento não
apareceriam em revisão de código nenhuma — apareceram na tela e no `ls` da pasta.
