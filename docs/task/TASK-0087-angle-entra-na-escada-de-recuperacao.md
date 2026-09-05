# TASK-0087: o ANGLE entra na escada de "a imagem não apareceu", antes do software

- **Status:** concluída
- **Criada em:** 2026-09-05
- **Concluída em:** 2026-09-05
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum — ela **não** corrige a
  [tela preta do GL em Mali-G52 r38](../bugs/open/armsx2-fork/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md),
  cuja causa é o driver da ARM; ela dá ao usuário uma saída **em hardware**
- **Commit:** — (o vínculo é o prefixo `TASK-0087:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

A [TASK-0083](TASK-0083-escolha-de-angle-por-jogo-chega-ao-core.md) mediu, no `SM-A127M`
(Mali-G52 r38p1), que o **mesmo jogo, no mesmo aparelho, com as estatísticas de GS byte a byte
idênticas**, renderiza via ANGLE e fica preto no driver GLES nativo da ARM. E ela deixou anotado que
pôr o ANGLE na escada da [TASK-0082](TASK-0082-acao-de-imagem-nao-apareceu-troca-o-backend.md) era a
continuação natural, mas que mexe numa escada já validada em aparelho e merece task própria.

Esta é a task.

## O problema concreto

A escada de hoje é: `auto` → oposto do veredito → `software` → `auto`. Neste par jogo/aparelho os
**dois** backends de hardware falham — o OpenGL fica preto, o Vulkan dá `VK_ERROR_DEVICE_LOST` —,
então o usuário desce até `software`, que roda e é lento. O ANGLE é uma terceira saída, **em
hardware**, e ela estava fora da escada.

## O desenho, e por que assim

**ANGLE não é um backend.** É uma implementação de GL, escolhida por um booleano
(`Settings.useAngleOpenGL`) ortogonal ao `renderer`, e que só tem efeito com `renderer == "opengl"`
— é o que `AngleDriver.decide` já diz. Portanto o passo da escada deixou de ser uma `String` e
passou a ser um par: `RendererRecovery.Step(renderer, useAngle)`.

**Ele entra imediatamente antes do `software`.** A ordem fica:

| gravado | próximo | por quê |
|---|---|---|
| `auto` | o oposto do que o `auto` escolheu | o que falhou foi a escolha do `auto` |
| igual ao que o `auto` escolheria | o oposto | trocar é o passo útil |
| o oposto do `auto` | **OpenGL + ANGLE** | a troca de backend já foi tentada; resta trocar a implementação de GL |
| OpenGL + ANGLE | `software` | acabou o hardware |
| `software` | `auto` | fecha o ciclo |

`software` continua sendo o último degrau de hardware-nenhum, porque é o único que sempre funciona.

**O passo do ANGLE é pulado quando as `.so` não estão no APK.** `AngleDriver` já distingue
`MissingLibs` de `Off`; propor um degrau que não pode funcionar transformaria a escada num beco.

**Nada de estado novo em disco.** A escada continua derivada do que já está gravado para o jogo —
agora `renderer` **e** `useAngleOpenGL`, ambos campos que já existem e que o usuário pode mudar pela
aba Renderizador.

## Escopo

**Entra:**

- `RendererRecovery`: `Step(renderer, useAngle)` e `nextStep(stored, storedAngle, verdict,
  angleAvailable)`. `nextBackend` continua existindo como a visão sem ANGLE, para não quebrar o que
  já a usa.
- `EmulationMenuViewModel`: o alvo pendente passa a ser um `Step`; ao confirmar, grava
  `renderer` **e** `useAngleOpenGL`.
- `EmulationMenuScreen`: o rótulo do botão passa a nomear o degrau do ANGLE, porque a ação só vale
  se disser o que vai acontecer **antes** do toque.
- Testes de unidade cobrindo a escada nova, incluindo o pulo quando o ANGLE não está disponível.

**NÃO entra:**

- **Ligar ANGLE sozinho em Mali r38.** Seria alcance global a partir de um jogo num telefone — o
  movimento que a [TASK-0072](TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md) desfez e
  que o [plano gráfico](../plano-grafico-mali-convergencia-upstream.md) proíbe.
- Amostrar pixels para detectar a tela preta. Proibido, e pelo motivo medido: 38 falsos positivos.
- Fechar o bug da tela preta. A causa é do driver da ARM.

## Como validar

No `SM-A127M`, partindo da tela preta de verdade (007 `SLUS-20751` em `auto`, que resolve para
OpenGL): percorrer a escada pelo menu e mostrar, por log e captura, que ela chega ao ANGLE e que a
**imagem aparece em hardware** — `GL_VENDOR: Google Inc. (ARM)`, e não `software`.

## Resultado

O degrau existe, está rotulado e foi percorrido no aparelho.

### O caminho, medido

`SM-A127M`, `githubDebug`, Delta Force — Black Hawk Down: Team Sabre (`SLUS-21414`), com
`renderer=vulkan` gravado (o degrau anterior ao ANGLE):

1. O menu de pausa oferece **"A imagem não apareceu — Reiniciar usando OpenGL (ANGLE)"**. O alvo é
   nomeado antes do toque, que é o que separa esta ação de um "conserta aí" opaco.
2. A confirmação repete o alvo e diz a consequência (*"O progresso desde o último save é perdido"*).
3. Confirmando, o que ficou gravado — **só para aquele jogo** — foi
   `{"useAngleOpenGL":true,"renderer":"opengl"}`: os **dois** campos, como o desenho exige.
4. E o que subiu:

```
@@ANGLE@@ enabled renderer=opengl useAngle=true
GL_VENDOR: Google Inc. (ARM)
GL_RENDERER: ANGLE (ARM, Vulkan 1.3.213 (Mali-G52 (0x72120000)), Mali-G52-38.1.0)
```

Com imagem na tela e OSD marcando `OpenGL HW` — **hardware**, não `software`. Era exatamente o
buraco: sem este degrau, a escada desceria daqui direto para o renderizador por software.

5. Saindo do degrau, a escada segue para `software`, e a chave do ANGLE é desligada junto — coberto
   por teste.

### Um ANR apareceu, e virou relatório próprio

Ao abrir o menu de pausa **sob ANGLE**, o sistema mostrou *"RetroSystem PS2 não está respondendo"*
(`Input dispatching timed out`). O app sobreviveu. O controle é o que dá peso: mesmo jogo, mesmo
gesto, `@@ANGLE@@ off` → **nenhum ANR**.

É **uma** ocorrência contra **um** controle, com confundidores fortes (APK `debuggable`, onde o ART
recusa AOT e 44,8% da CPU da UI é interpretador; aparelho a 46% de velocidade). Não é prova, e está
registrado como sinal em
[`anr-ao-abrir-o-menu-de-pausa-sob-angle`](../bugs/open/armsx2-fork/anr-ao-abrir-o-menu-de-pausa-sob-angle_2026-09-05T01-51.md),
com os próximos passos na ordem de custo.

**O degrau não foi removido por causa disso**, e a decisão é deliberada: no ponto da escada em que
ele entra, a alternativa é `software`. Um ANR não reproduzido, num APK que nem é o de release, não
justifica trocar uma saída em hardware por uma em software.

### De quebra: a lacuna da TASK-0085 fechou

Aquela task registrou como **não provado** que o campo `GPU` do `PerfLog` varia com a carga — duas
amostras, 22% nas duas. Nesta validação, com 007 em OpenGL, o campo saiu em **9%, 59%, 61%, 62%,
63% e 67%**. Varia numa faixa larga: a medição acompanha carga, não é ruído preso num número.

### O que NÃO foi provado

- **A escada inteira num único percurso contínuo** partindo da tela preta do 007. O degrau do ANGLE
  foi exercitado a partir de `vulkan` gravado, que é um estado legítimo da escada e o degrau
  imediatamente anterior — mas não é a mesma coisa que tocar o botão três vezes seguidas.
- **Que o ANGLE resolve a tela preta do 007.** Isso a [TASK-0083](TASK-0083-escolha-de-angle-por-jogo-chega-ao-core.md)
  já mediu; aqui o que se provou foi a escada chegar até ele.
- **O caso `angleAvailable = false`** — coberto por teste de unidade, não por aparelho: exigiria um
  APK sem as `.so` do ANGLE.
