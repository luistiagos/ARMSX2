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

## Validado em aparelho — 2026-09-04

Galaxy A12 `SM-A127M`, `githubDebug`. A prova está no próprio disco do aparelho, e ela é datada.

**A TASK-0045 entrou em 2026-08-28** (`186cdde1c6`). Listando `roms/` por data e extensão:

| data | extensão | arquivo |
|---|---|---|
| **2026-08-28** | **`7z`** | `10.000 Bullets (Europe) (En,Fr,De,Es,It).7z` — 1,4 GB |
| **2026-08-28** | **`part`** | `10.000 Bullets (Europe) (En,Fr,De,Es,It).iso.part` — 1,1 GB |
| 2026-08-28 | `bin`, `chd` | 10 Pin Champions Alley, 007 Everything or Nothing |
| 2026-08-31 | `chd` ×4, `iso` | 007 Agent Under Fire, Delta Force ×2, Lara Croft, God of War 2 |
| 2026-09-03 | `chd`, `iso` ×2 | Adventures of Darwin, 120円の春, 3LDK |
| 2026-09-04 | `bin`, `iso` | 3D Kakutou Tkool 2, _summer Double Sharp |

Duas leituras, e as duas importam:

1. **A impressão digital do defeito está lá, e é do dia da correção.** O único `.7z` do aparelho e um
   `.iso.part` órfão são **do mesmo jogo** — `10.000 Bullets`, que é literalmente um dos dois títulos
   nomeados no relato do usuário. Um `.7z` baixado ao lado de um `.iso` parcial do mesmo jogo é
   exatamente o que este relatório descreve: a fonte comprimida escolhida, e o conteúdo indo para o
   nome `.iso` do manifesto.

2. **Depois da correção, nada mais caiu fora da lista.** Doze downloads completos entre 31/08 e
   04/09 — `chd` ×5, `iso` ×4, `bin` ×2 —, **todos** em extensão que o `GetFileReader` do
   `InputIsoFile.cpp` sabe abrir. Dois deles foram baixados **hoje**. Nenhum `.7z` ou `.zip` novo.

**E um deles boota.** Lara Croft Tomb Raider — Anniversary (`SLUS-21555`, CHD, CRC `B639EB17`),
baixado em 31/08, abre e renderiza: OSD com `OpenGL HW`, 25,9 fps, `640x448 NTSC Interlaced`. Um CHD
gravado sob nome `.iso` cairia no `FlatFileReader` e falharia como disco corrompido — que era o
segundo modo de falha descrito aqui.

### O limite desta validação

**Não exercitei o caminho de extração da [TASK-0048](../../task/TASK-0048-descompactar-7z-e-zip-no-download.md)**
— aquele em que a cascata inteira falha, um comprimido é a única fonte, e ele precisa ser baixado e
descompactado. Ele está em `VARIANT_EXTENSIONS` no fim da lista de propósito, e nenhum dos downloads
recentes precisou dele. Provar esse caminho exige um título cuja única fonte seja `.7z`, e a
TASK-0048 segue `em andamento` por isso.

O `3LDK ... .iso.part` de 31/08 é download interrompido, não este defeito — e o mesmo título aparece
completo como `.iso` em 03/09, ou seja, a retomada gravou na extensão certa.
