# TASK-0026: embarcar a BIOS no APK, como na versão anterior

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0026:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Um jogo baixado do catálogo era lançado e voltava direto para a biblioteca. O diagnóstico foi
"não há BIOS instalada neste aparelho" — e estava errado sobre a causa: o usuário respondeu que
**a BIOS é embarcada, igual na versão antiga**. Era o produto, não o aparelho.

## O que faltava

Só o arquivo. O fork já chama `copyAssetAll(applicationContext, "bios")` no arranque — a máquina de
extração está inteira lá, apontando para `assets/bios`. A pasta é que não existia.

`assets/bios/SCPH-90001_BIOS_V18_USA_230.bin` (4 MiB), copiada da árvore anterior — mesmo md5
(`21038400dc633070a78ad53090c53017`), byte a byte.

Não foi preciso `noCompress`: a árvore anterior também não tinha, e a extração passa por
`AssetManager.open`, que descomprime sozinho. `MainActivity.copyFile` pula arquivo já existente
(exceto shaders e GameDB, que forçam atualização), então a cópia acontece uma vez e não a cada
arranque.

## O que esta task diz sobre o processo

**Este item não estava no plano de migração** ([`plano-fork-sobre-upstream.md`](../../platforms/android/../../docs/plano-fork-sobre-upstream.md) §4).
O plano inventariou Java, C++ e identidade visual, e conferiu os assets pelo que estava escrito
sobre eles — o manifesto do catálogo. Não comparou as duas pastas `assets/` arquivo a arquivo, e por
isso um arquivo de 4 MiB, sem o qual nenhum jogo roda, passou despercebido.

A comparação que o pega é de uma linha:

```bash
comm -23 <(cd antigo/assets && find . -type f | sort) <(cd fork/assets && find . -type f | sort)
```

Foi ela que produziu o inventário definitivo depois — e o resultado dela também derrubou três
suspeitas (FSR1, fontes, GameDB), que **já estão no fork**, vindas de `bin/resources` em tempo de
build.

## Como validar

Instalado no Galaxy A12 e conferido no disco do aparelho:

```
/sdcard/Android/data/come.nanodata.armsx2/files/bios/SCPH-90001_BIOS_V18_USA_230.bin   4194304
```

**Validado depois, na [TASK-0034](TASK-0034-campo-de-busca-no-topo.md):** o jogo baixado do
catálogo roda — BIOS, FMV de abertura e tela de título, com o menu de emulação identificando
`SLUS-20265 · CRC 79646C72` lido do disco. O ciclo catálogo → download → biblioteca → jogar está
provado ponta a ponta.

## Resultado

Entregue.
