# Bug: shader CAS não compila em Mali — falta qualificador de precisão no header GLES

- **Detectado em:** 2026-08-24 17:08 (teste dirigido em aparelho)
- **Origem:** Samsung Galaxy A12 (`SM-A127M`), Android 13, Exynos 850 / Mali-G52, driver `v1.r38p1`
- **Errors (serviço):** nenhum — falha silenciosa, o recurso apenas se desliga
- **Classe:** fail
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0007](../../task/TASK-0007-cas-precisao-gles.md)

## Sintoma

Ao abrir um jogo, o emulador grava `files/logs/pcsx2_bad_shader_1.txt` (231 KB) contendo o fonte do
shader CAS e, ao final:

```
Compile failed, info log:
0:27: S0032: no default precision defined for variable 'imgDst'
```

O jogo roda normalmente — `CreateCASPrograms()` devolve `false` e apenas marca
`m_features.cas_sharpening = false`. O usuário não é avisado; o sharpening simplesmente nunca
funciona em Mali, e um dump de 231 KB é escrito a cada boot.

## Causa raiz

Confirmada em [`GSDeviceOGL.cpp:2209-2222`](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L2209).
O header GLES do CAS é montado com **apenas** a diretiva de versão:

```cpp
if (GLAD_GL_ES_VERSION_3_2)
    header = "#version 320 es\n";
else if (GLAD_GL_ES_VERSION_3_1)
    header = "#version 310 es\n";
// No extension needed for compute on GLES 3.1/3.2.
```

Em GLSL ES não existe precisão padrão para tipos de imagem, então
`layout(binding=0, rgba8) uniform writeonly image2D imgDst;` (linha 25 de `shaders/opengl/cas.glsl`)
é inválido sem um `precision` explícito. O compilador da ARM aplica a regra; drivers mais permissivos
deixam passar, o que é por que isso não aparece em outros aparelhos.

O caminho de shaders TFX já faz isso certo — [`GSDeviceOGL.cpp:1446-1452`](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1446)
injeta `precision highp float/int/sampler2D`. O header do CAS foi escrito sem essa parte.

**É defeito nosso, não do upstream.** O `CreateCASPrograms()` do upstream não tem ramo GLES nenhum —
usa só `#version 420` de desktop. O suporte GLES para CAS é adição deste fork, e saiu incompleta.

## Como reproduzir

Abrir qualquer jogo num aparelho Mali com OpenGL e verificar
`<DataRoot>/logs/pcsx2_bad_shader_1.txt`.

## Correção proposta

Acrescentar os qualificadores de precisão ao header GLES do CAS, no mesmo padrão já usado pelo
caminho TFX — no mínimo `precision highp float;`, `precision highp int;`,
`precision highp image2D;` e `precision highp sampler2D;`. Depois disso, confirmar no A12 que o dump
deixa de ser gerado e que o sharpening passa a funcionar.

Vale também considerar enviar a correção ao upstream junto com o ramo GLES, já que lá o CAS
simplesmente não existe em GLES.

## Correção validada — 2026-08-24

Corrigido pela [TASK-0007](../../task/TASK-0007-cas-precisao-gles.md): quatro qualificadores de
precisão (`float`, `int`, `sampler2D`, `image2D`) no header GLES de `CreateCASPrograms()`.

Validado no Galaxy A12 (`SM-A127M`, Android 13, Mali-G52, driver `v1.r38p1`), com os dumps antigos
apagados antes do teste:

1. **Nenhum `pcsx2_bad_shader_*.txt` foi gerado.** A pasta `logs/` ficou só com `androidlog.txt`.
2. A linha de diagnóstico da [TASK-0006](../../task/TASK-0006-diagnostico-boot-gs.md) passou de
   `cas=0` para **`cas=1`**.
3. Sem regressão visual: MGS3 seguiu do FMV até os dois logos da Konami, renderizados corretamente.

Vale registrar, para não confundir leituras futuras: `cas=1` é **capacidade**, não uso. O INI do
aparelho tem `CASMode = 0` (Disabled), então o shader compila e fica disponível, mas não roda no
pipeline. Isso também foi o que descartou a hipótese de a correção ter causado a tela preta
observada durante o teste — o preto era o próprio intro do jogo, que tem trechos pretos longos.
