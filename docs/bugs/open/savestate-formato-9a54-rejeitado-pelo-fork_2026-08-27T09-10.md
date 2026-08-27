# Bug: os savestates da 1.0.23 (`0x9A54`) são rejeitados pelo fork

- **Detectado em:** 2026-08-27 09:10 (análise durante a migração para o fork)
- **Origem:** `pcsx2/SaveState.cpp` (portão de versão) + `pcsx2/SaveStateLegacy.cpp`
- **Errors (serviço):** nenhum — não é crash; é rejeição limpa, com mensagem
- **Classe:** regressão de compatibilidade (dados do usuário)
- **Reincidência:** aparece na primeira publicação do fork; não existe na 1.0.23

## Sintoma

Todo savestate gravado pela 1.0.23 e anteriores deixa de carregar depois da atualização para o
fork. O usuário vê "save incompatível" e perde o progresso não salvo em memory card.

```cpp
// SaveState.cpp — o corte é na palavra ALTA da versão, sem faixa de compatibilidade
if (savever > g_SaveVersion || (savever >> 16) != (g_SaveVersion >> 16))
    // rejeita
```

`0x9A54` (nosso) contra `0x9A59` (o do fork) → rejeição dura.

**Não há perda de arquivo:** os `.p2s` continuam no disco, íntegros. O que falta é o app saber lê-los.

## O que já foi medido

A análise completa está em [`docs/savestates-preservar-no-transplante.md`](../../savestates-preservar-no-transplante.md)
(trazida da árvore anterior). O resumo dela:

- 54 dos 56 arquivos que participam da serialização têm sequência de wire **idêntica**;
- os 2 que diferem não são mudança de formato (uma renomeação pura em `SIO/Sio2.cpp`, e um struct
  de wire em `SaveState.cpp` que o próprio upstream criou **para o formato não mexer**);
- a única quebra real é o alargamento dos contadores de ciclo de `u32` para `u64`, e o upstream já
  tem o helper para isso: `WidenCycle`, em `SaveStateLegacy.cpp`, correto sobre a volta do contador
  de 32 bits (que uma extensão-com-zero ingênua erraria).

## ⚠️ A abordagem que aquele documento propõe está ERRADA

Ele propõe acrescentar `0x9A54` ao `SaveStateLegacy` como variante da era `0x9A34` (NetherSX2),
porque três dos quatro blocos de registradores coincidem. **Duas verificações feitas em 2026-08-27
derrubam isso:**

| O que o leitor assume para `0x9A34` | O que a nossa era tem |
|---|---|
| `MICROREGINFO_9A34 = 160` bytes | `microRegInfo` de **96** bytes (`x86/microVU_IR.h:61`) |
| SIO como despejo cru de classe (`SIO0_BLOB_9A34 = 32`) | `Sio0::DoState(StateWrapper&)` — o formato **moderno** |

Seguir o caminho do `0x9A34` faria o leitor pular **128 bytes a mais** no bloco do vuJIT, e a partir
dali todo o resto do blob desincroniza. O resultado não seria um erro: seria um jogo carregando com
estado corrompido — **pior que a rejeição de hoje**.

## Hipótese corrigida

`0x9A54` não é uma era antiga: é praticamente **o formato atual do fork menos o alargamento dos
contadores de ciclo**. Os registradores coincidem com o `0x9A34` só porque não mudaram entre as
eras; o resto do arquivo já segue o layout novo.

Se isso se confirmar, o desenho certo **não** é estender o `SaveStateLegacy`, e sim uma passagem
estreita no leitor moderno: aceitar `0x9A54` e aplicar `WidenCycle` aos campos de ciclo, deixando
todo o resto no caminho normal. Bem menor — e sem os riscos de um desserializador paralelo.

**Confirmar antes de codificar**, bloco a bloco (contadores, VU, MTVU, IPU, GIF, SIF, SPR, CDVD),
que o resto é de fato invariante. Foi justamente pular esse passo que produziu a proposta errada.

## Como validar uma correção

Precisa de um savestate `0x9A54` **real**. Não há como fabricá-lo: exige instalar a 1.0.23, rodar um
jogo e salvar. E instalar a 1.0.23 sobre o fork exige **desinstalar** (versionCode 38 não desce para
37), o que apaga os dados do aparelho — inclusive as ROMs baixadas.

Sem esse arquivo, **não publicar** uma correção: um leitor de savestate não verificado troca uma
falha visível por uma silenciosa.

## Situação

Aberto, sem correção. O comportamento atual (rejeição limpa) é seguro: nenhum arquivo é perdido, e
o usuário recebe uma mensagem em vez de um estado corrompido.
