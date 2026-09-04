# Bug: Mali-G52 r38 no Vulkan perde o device com qualquer upscale > 1x, e o emulador aborta

- **Detectado em:** 2026-09-02 11:33 (tombstone puxado por `adb` e simbolizado)
- **Origem:** Galaxy A12 `SM-A127M`, Exynos 850, Mali-G52, driver ARM r38p1, 007: Everything or
  Nothing (`SLES-52046`), APK `githubDebug`
- **Errors (serviço):** é crash — **gera** telemetria, ao contrário dos outros três defeitos deste
  aparelho
- **Classe:** crash
- **Reincidência:** 3 ocorrências, 2 escalas diferentes
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma — ver "Por que não há conserto do nosso lado"

## Sintoma

Com o renderizador em **Vulkan** e o upscale acima de 1x, o app fecha sozinho durante o jogo.

| upscale | resultado |
|---|---|
| 1x nativo | não crasha (mas tem o defeito de linhas) |
| 1.25x | sobrevive mais tempo, **crasha** |
| 2x | **crasha** |

"Sobrevive mais tempo" com escala menor é assinatura de pressão de recurso, não de um caminho de
código específico.

## Causa, simbolizada

Tombstone puxado do aparelho e resolvido contra o `.so` de Debug com BuildId idêntico
(`5c5e7bdb301055bb7dc33ca884c4d966b15a366c`):

```
GS thread · signal 6 (SIGABRT)
  #01 AbortWithMessage        common/HostSys.cpp:207
  #02 pxOnAssertFail          common/Assertions.cpp:116
  #03 BeginPresentFrame       pcsx2/GS/Renderers/Common/GSRenderer.cpp:756
  #04 GSRenderer::VSync       pcsx2/GS/Renderers/Common/GSRenderer.cpp:998
  #05 GSRendererHW::VSync     pcsx2/GS/Renderers/HW/GSRendererHW.cpp:164
  #06 SubmitVsync · #07 GSvsync · #08 MTGS::MainLoop
```

`GSRenderer.cpp:756` é:

```cpp
pxFailRel("Host GPU lost too many times, device is probably completely wedged.\n{...}")
```

Ou seja: o driver devolve `VK_ERROR_DEVICE_LOST`, o `BeginPresentFrame` recria o device, ele morre
outra vez **em menos de 15 segundos**, e o PCSX2 aborta de propósito — o comentário no código diz
que sem isso viraria laço de reset infinito com vazamento.

As três pilhas são **idênticas byte a byte** (mesmos offsets), em 11:24:39, 11:25:45 e 11:33:28.
Determinístico.

## Por que não há conserto do nosso lado

O `pxFailRel` não é o defeito, é o para-quedas. O defeito é o driver perder o device duas vezes
seguidas, e isso acontece **abaixo** de qualquer código nosso ou do PCSX2. Remover o aborto só
trocaria o crash por um laço de recriação de device.

Isto é dado sobre o hardware, não uma tarefa de correção: **este blob não sustenta upscale neste
título.**

## A consequência que fecha a investigação das linhas verticais

O GameDB do PCSX2 tem, para este serial exato:

```yaml
SLES-52046:
  gsHWFixes:
    halfPixelOffset: 2   # Fixes lines in cutscenes.
```

O banco nomeia o sintoma. Mas `halfPixelOffset` é **exclusivamente de upscaling**, verificado nos
quatro sítios que o consultam — inclusive nos modos que se **chamam** `Native` e `NativeWTexOffset`,
que apesar do nome também exigem `scale > 1.0f`
([GSRendererHW.cpp:6331](../../../../pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L6331),
[8368](../../../../pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L8368),
[8439](../../../../pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L8439),
[8556](../../../../pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L8556)).

> Uma hipótese minha caiu aqui e fica registrada: eu suspeitei que
> `Pcsx2Config::GSOptions::MaskUpscalingHacks()` fosse larga demais por zerar também os modos
> "Native". **Não é.** Abri os quatro sítios e todos exigem escala > 1. A máscara está correta.

Somando as duas metades:

1. o fix que o GameDB indica para as linhas deste jogo **só existe acima de 1x**;
2. **acima de 1x este aparelho crasha.**

Portanto o fix é **inalcançável neste hardware**. Não é uma correção que falta escrever — é um teto.

### Adendo (2026-09-04): uma causa alternativa foi eliminada por medição

A outra hipótese para as mesmas linhas — o piso de Z de 32 bits do PS2, que o `GSDeviceVK`
desliga em todo Mali — **caiu no aparelho**. Com o opt-out da
[TASK-0064](../../../task/TASK-0064-devolver-o-controle-do-piso-de-z.md) ligado, o piso volta
(o token `no_ps2_z_quantization` some do log) e a imagem é a mesma: 0,20 % de diferença na
métrica de listra no par de quadros mais bem casado. Registro em
[`done/`](../../done/mali-vulkan-desliga-o-piso-de-z-do-ps2-sem-volta_2026-08-31T16-30.md).

Isso reforça a leitura desta seção, e uma correção de detalhe: a cópia deste aparelho é a
**NTSC-U `SLUS-20751`**, não a `SLES-52046` do cabeçalho. O GameDB dá as **mesmas** `gsHWFixes`
aos dois seriais (`halfPixelOffset: 2`, `preloadFrameData: 1`), então o raciocínio acima vale
igual — mas quem for repetir o teste deve procurar o serial certo. O log dos dois braços do A/B
mostra `hpo=0` em 1x, exatamente como esta seção prevê.

## O quadro completo deste aparelho, neste título

| renderizador | 1x nativo | upscale > 1x |
|---|---|---|
| OpenGL | tela preta | não testado (preto já em 1x) |
| Vulkan | linhas verticais | **crash** (este bug) |
| **Software** | **correto** | — |

Para este título neste aparelho, **o renderizador de software é a única configuração correta**.
Custa desempenho, e num A12 com o clock cortado pelo GOS isso provavelmente não é jogável — mas é
o que a medição diz, e é melhor do que continuar procurando um conserto que o hardware não permite.

## O que este registro NÃO afirma

Não afirma que Mali-G52 r38 perde o device com upscale em **todo** jogo — foi medido em um título.
O *10 Pin - Champions Alley* não foi testado com upscale. Antes de qualquer regra ou aviso genérico
sobre upscale nesse GPU, isso precisa ser medido em mais títulos: é exatamente o erro que a
[TASK-0072](../../../task/TASK-0072-retirar-a-regra-auto-vulkan-do-banco-de-drivers.md) acabou de
desfazer.
