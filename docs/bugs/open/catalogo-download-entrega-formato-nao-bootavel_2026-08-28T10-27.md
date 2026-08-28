# Bug: o download entrega .7z (e CHD de outra região) com o nome .iso do manifesto

- **Detectado em:** 2026-08-28 10:27 (relato do usuário: "10.000 Bullets e 187 - Ride or Die não
  iniciam depois de baixados")
- **Origem:** `catalog/RomDownloadManager.java` — `resolveDownloadUrl` (retry por extensões
  variantes) + `doDownloadLocked` (grava sempre em `entry.getLocalFile`)
- **Errors (serviço):** nenhum — não é crash; o emulador abre e não acha disco
- **Classe:** dado corrompido por construção (o arquivo no disco não é o que o nome diz)
- **Reincidência:** primeira vez registrada; existe desde que o retry por variantes entrou
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0045](../../task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md)

## Sintoma

Jogo baixado pelo catálogo aparece na biblioteca, o toque abre o emulador e nada roda.

## Prova, colhida do aparelho de teste (SM-A127M, 1.0.24 / versionCode 38)

Os primeiros bytes de tudo que está em `files/roms/`:

| Arquivo no disco | Magic | O que é de verdade | Boota |
|---|---|---|---|
| `10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` | `37 7a bc af 27 1c` | **7-Zip** | não |
| `187 - Ride or Die (Europe, Australia) (En,Fr,De,Es,It).iso` | `37 7a bc af 27 1c` | **7-Zip** | não |
| `007 - Agent Under Fire (Europe) (En,Fr,De,Es,Nl,Sv).iso` | `37 7a bc af 27 1c` | **7-Zip** | não |
| `007 - Everything or Nothing (Europe) (Fr,De).iso` | `37 7a bc af 27 1c` | **7-Zip** | não |
| `007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).iso` | `4d 43 6f 6d 70 72` | **CHD**, e da versão **USA** | não |
| `007 - Agent Under Fire (Korea).iso` | `01 43 44 30 30 31` no LBA 16 | ISO real (mas é outro dump) | sim |
| os `.chd` do catálogo curado | `MComprHD` | CHD | sim |

São ~9,5 GB baixados que não rodam.

## Causa raiz

Duas, no mesmo arquivo.

**1. O retry por extensões variantes tenta `.7z` primeiro e grava com o nome do manifesto.**

```java
String[] variants = new String[]{ ".7z", ".zip", ".rar", ".chd", ".iso" };
...
File finalFile = entry.getLocalFile(destDir);   // sempre o nome do manifesto
```

Medido no endpoint: `download_sources?path=10.000 Bullets (Europe) (En,Fr,De,Es,It).iso` devolve
`{"sources":[]}`; o **mesmo nome com `.7z`** devolve um link do archive.org. O app baixa esse 7z e o
grava como `.iso`. Idem para o 187.

**2. `by_alias` resolve por TÍTULO e devolve outro arquivo, de outra região e outro formato.**

Para `007 - Quantum of Solace (Europe, Australia)…iso` ele devolve
`007 - Quantum of Solace (USA).chd` — o `size` da resposta bate byte a byte com o arquivo que ficou
no aparelho. O conteúdo é bom; o nome é que mente.

**Por que um nome errado impede o boot:** o CDVD escolhe o reader pela **extensão**
(`GetFileReader`, `pcsx2/CDVD/InputIsoFile.cpp:40`). Um CHD chamado `.iso` cai no `FlatFileReader` e
falha exatamente como o 7z.

## Tamanho do problema

O manifesto tem 9.077 `.iso`, 3.466 `.chd` e 85 `.7z`. Numa amostra de 10 entradas `.iso`:

- **0** têm fonte com o próprio nome `.iso` (ou `.chd`);
- 5 só existem em `.7z`;
- 5 não têm fonte nenhuma e caem no fallback do HuggingFace, que responde **404**.

Ou seja: quase tudo que é `.iso` no catálogo hoje ou não baixa, ou baixa algo que não roda. O que
funciona é o bloco `.chd` curado.

## Situação

Endereçado pela [TASK-0045](../../task/TASK-0045-baixar-so-formato-bootavel-e-manter-a-capa.md), que
para de aceitar fonte que o emulador não abre e passa a gravar com a extensão do conteúdo recebido.
Continua **aberto** até a validação no aparelho, e **não** cobre a limpeza do manifesto (as ~9k
entradas `.iso` sem fonte utilizável seguem no catálogo, agora falhando cedo em vez de baixar lixo).
