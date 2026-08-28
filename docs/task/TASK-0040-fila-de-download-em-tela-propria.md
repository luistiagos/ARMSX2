# TASK-0040: dar tela própria à fila de download, como a aba "Salvos" da versão anterior

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [fila-download-congela-tela-ao-pausar](../bugs/done/fila-download-congela-tela-ao-pausar_2026-08-27T21-30.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0040:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Relato do usuário, sobre a entrega da [TASK-0038](TASK-0038-fila-de-download-visivel.md): *"ao
iniciar o download tudo fica na mesma tela da grid do catálogo, o ideal seria colocarmos em uma tela
distinta igual é feito na aplicação antiga."*

Procede. A TASK-0038 trouxe o conteúdo certo para o lugar errado: empilhou a fila **acima da grade**,
onde ela empurra a biblioteca para baixo e disputa espaço com 12.628 cartões. Na `version1` a fila
nunca dividiu tela com o catálogo.

## Como era na versão anterior

`HomeActivity` tinha um `BottomNavigationView` de duas abas
(`res/menu/menu_home_bottom_nav.xml`):

| Aba | Ícone | Conteúdo |
|---|---|---|
| `nav_catalog` | `ic_cd_24` | a grade do catálogo (`rv_catalog`) |
| `nav_saved` | `ic_download_24` | fila em andamento **+** jogos já salvos (`scroll_saved`) |

E o toque num cartão **levava para lá** — `onEntryClick`:

```java
} else if (entry.queueState == QUEUED || DOWNLOADING || PAUSED) {
    // Already in queue — navigate to Saved tab so user sees the card
    bottomNav.setSelectedItemId(R.id.nav_saved);
} else {
    DownloadQueueManager.get().enqueue(entry);
    bottomNav.setSelectedItemId(R.id.nav_saved);   // switch to Saved tab
}
```

Ou seja: iniciar um download **trocava de tela**. É essa parte que falta.

## Escopo

**Entra:**

1. `AppRoute.Downloads`, com entrada na gaveta (ao lado da Biblioteca) e o mapeamento de seleção.
2. `ui/catalog/DownloadsScreen.kt` — tela própria, com barra de topo e seta de voltar, hospedando as
   mesmas linhas de fila da TASK-0038 e um estado vazio quando não há nada baixando.
3. **Sai da grade:** a seção deixa de ser emitida na `LazyVerticalGrid` da biblioteca.
4. Confirmar "Baixar" no painel passa a **navegar para a tela de downloads**, como a `version1`
   fazia ao trocar para a aba Salvos.
5. Tocar num jogo **que já está na fila** vai direto para a tela de downloads, em vez de abrir o
   painel — também como a `version1`. Pausar/retomar/cancelar moram lá agora.

**Fica de fora, deliberadamente:**

- **O painel de confirmação continua** para um jogo ainda não enfileirado. A `version1` enfileirava
  no primeiro toque; o fork pergunta antes porque uma ROM de PS2 tem de 1 a 10 GB, e essa decisão é
  da [TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md) — não se desfaz de passagem.
- **Barra de navegação inferior de duas abas.** O fork navega por gaveta desde a
  [TASK-0033](TASK-0033-enxugar-menu-lateral.md); acrescentar uma `BottomNavigationView` só para
  esta tela seria uma segunda arquitetura de navegação convivendo com a primeira.
- **Contador/badge no item da gaveta.** Útil, mas é outra decisão de UI e não faz parte do relato.
- A tarja de progresso no cartão da grade **fica** — é o equivalente ao
  `updateCatalogTileProgress` da `version1`, que mantinha a barrinha no cartão do catálogo mesmo
  com a fila noutra aba.

## Como validar

No SM-A127M, APK `github/release`:

1. Biblioteca → tocar num jogo do catálogo → **Baixar**.
2. O app **muda para a tela Downloads**, com o item em `Iniciando…`/`0%`.
3. Voltar (seta ou Back) → a biblioteca aparece **sem** a seção de fila empurrando a grade.
4. A tarja do cartão continua andando (`%`).
5. Tocar de novo no mesmo jogo → vai direto para Downloads, sem painel.
6. Gaveta → **Downloads** abre a tela; com a fila vazia, mostra o estado vazio.
7. Pausar/retomar/cancelar seguem funcionando na tela.

## Resultado

Entregue. SM-A127M (Android 13), APK `github/release`:

| Passo | Resultado |
|---|---|
| Biblioteca → jogo do catálogo → **Baixar** | O app **muda para a tela Downloads**, `0,9 MB de 2103,9 MB (0%)` |
| Progresso na tela nova | Anda sozinho, batendo com o `.part` |
| BACK | Biblioteca **sem** a seção empurrando a grade |
| Tarja do cartão | Continua andando (`18%`) |
| Tocar de novo no mesmo jogo | Vai direto para Downloads (`43%`), sem painel |
| Gaveta → **Downloads** | Abre a tela; fila vazia mostra "Nenhum download" |
| Pausar / retomar / cancelar | `Pausado` → retoma de 216 MB para 246 MB → `.part` apagado |

### O que apareceu no caminho

Pausar **congelava o app inteiro** — tela escura, surda a toque e a BACK, com um GC de 12–41 MB a
cada 250 ms. Não era desta task: era um defeito latente da
[TASK-0038](TASK-0038-fila-de-download-visivel.md) que só se manifesta aqui.

O botão primário era desenhado em dois ramos de `when` (`⏸` e `▶`) com o **mesmo `controllerId`**.
Trocar de ramo destrói e recria o registro no `SettingsControllerNav`, e `register`/`unregister`
escrevem `selectedId`/`selectedIndex` — estado que a composição lê para desenhar o foco. Ciclo
fechado. Na biblioteca não aparecia porque o foco do controle nunca pousava num botão da fila, e a
guarda `if (selectedId.value == id)` anulava a escrita; nesta tela os botões da fila são quase os
únicos controles.

Corrigido com **um** ponto de composição e parâmetros variáveis. Medido: de ~4 GCs/s para **0 GCs em
12 s** com download correndo. Detalhes e o trecho do logcat em
[`fila-download-congela-tela-ao-pausar`](../bugs/done/fila-download-congela-tela-ao-pausar_2026-08-27T21-30.md).

### Não validado

Nada ficou por validar dos sete passos. Segue de fora, como planejado: a barra inferior de duas
abas, o contador no item da gaveta, e o pedido de `POST_NOTIFICATIONS` em runtime (esse último
ainda pendente desde a TASK-0038).
