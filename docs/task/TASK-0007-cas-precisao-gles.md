# TASK-0007: Adicionar qualificadores de precisão ao header GLES do shader CAS

- **Status:** concluída
- **Criada em:** 2026-08-24
- **Concluída em:** 2026-08-24
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [cas-shader-gles-sem-precisao-mali](../bugs/done/cas-shader-gles-sem-precisao-mali_2026-08-24T17-08.md)
- **Commit:** assunto `TASK-0007:` — hash no índice de [`README.md`](README.md)
- **Revertida por:** —
- **Publicado em:** 1.0.23 / versionCode 37

## Objetivo

Fazer o shader CAS (Contrast Adaptive Sharpening) compilar em GPUs Mali. Hoje ele falha, o
sharpening é desligado em silêncio e um dump de 231 KB é gravado a cada boot.

## Escopo

**Entra:**
- Quatro linhas de precisão no header GLES de `GSDeviceOGL::CreateCASPrograms()`:
  `float`, `int`, `sampler2D` e `image2D`.

**NÃO entra:**
- O caminho desktop (`#version 420`), que não usa declarações de precisão.
- Alterar `shaders/opengl/cas.glsl`. O shader está correto; o header é que estava incompleto.
- Enviar a correção ao upstream. Vale fazer, mas é trabalho à parte — lá o `CreateCASPrograms()`
  não tem ramo GLES nenhum, então o defeito é exclusivamente nosso.

## Como validar

No Galaxy A12 (Mali-G52), abrir um jogo e confirmar que:
1. `<DataRoot>/logs/pcsx2_bad_shader_*.txt` **deixa de ser gerado**;
2. a linha `GSBoot:` da [TASK-0006](TASK-0006-diagnostico-boot-gs.md) passa a mostrar `cas=1`.

## Resultado

Correção aplicada e compilada. `ninja -j 4 bin/libemucore.so` → link OK, e os quatro literais estão
no binário:

```
precision highp float;
precision highp image2D;
precision highp int;
precision highp sampler2D;
```

**Validada no Galaxy A12** (`SM-A127M`, Mali-G52, driver `v1.r38p1`), com os dumps antigos apagados
antes do teste:

1. Nenhum `pcsx2_bad_shader_*.txt` gerado — `logs/` ficou só com `androidlog.txt`.
2. A linha `GSBoot:` passou de `cas=0` para **`cas=1`**.
3. Sem regressão visual: MGS3 seguiu do FMV até os dois logos da Konami, corretos.

Durante o teste a tela ficou preta por ~80 s e cheguei a suspeitar de regressão da própria correção.
Descartado ao abrir a configuração: `CASMode = 0` (Disabled) no INI do aparelho, ou seja, o shader
compila e fica disponível mas **não roda** — `cas=1` é capacidade, não uso. O preto era o intro do
jogo, que tem trechos pretos longos, e o emulador estava a 138% de CPU o tempo todo.

### Por que quatro linhas e não só `image2D`

O erro reportado pelo driver cita apenas `imgDst`, mas GLSL ES não define precisão padrão para
sampler nem para imagem, e `cas.glsl` também declara `sampler2D imgSrc`, `uvec4` e `ivec2`. Corrigir
só o que o compilador reclamou primeiro deixaria o próximo erro para o boot seguinte. É o mesmo
conjunto que o caminho TFX já injeta, mais `image2D`.

Declarações de precisão para tipos de imagem existem a partir de GLES 3.1, que é o mínimo já exigido
pelo ramo logo acima — então não há risco de regressão em aparelhos mais antigos: eles já saíam por
`return false` antes de chegar aqui.
