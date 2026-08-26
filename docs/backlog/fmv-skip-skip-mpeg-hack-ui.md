# Backlog: Expor SkipMPEGHack na UI de configurações

**Origem:** Análise da release ARMSX2 2.4.5 (`refresh-experimental`) — "Restored FMV-skip hack to ARM64 recompiler"  
**Data da análise:** 2026-06-30  
**Prioridade:** Média — melhora jogabilidade em títulos com FMVs problemáticos (Katamari Damacy e outros)

---

## Contexto

A release 2.4.5 menciona "Skip MPEG functionality — Restored FMV-skip hack to ARM64 recompiler". Após análise, conclui-se que:

- O `refresh-experimental` construiu um **EE recompiler nativo ARM64** — arquitetura diferente da nossa.
- **Nosso projeto** usa a abordagem x86 JIT → ARM64 translation (não tem EE recompiler ARM64 nativo).
- O "restore" deles foi re-adicionar a função ao recompiler ARM64 nativo deles — não se aplica à nossa arquitetura.

**A boa notícia:** o `SkipMPEGHack` já está **totalmente implementado e funcional** na nossa base de código.

---

## O que é o SkipMPEGHack

Existem dois mecanismos distintos para FMVs problemáticos:

| Hack | Como funciona | Quando usar |
|---|---|---|
| `SoftwareRendererFMVHack` | Detecta FMV no vblank e troca temporariamente para software renderer | FMVs com problemas visuais de renderização |
| `SkipMPEGHack` | Detecta o padrão `sceMpegIsEnd` no recompiler e pula os frames do vídeo | FMVs que causam **hang/freeze** do jogo |

O `SkipMPEGHack` age no nível do JIT: ao recompilar um bloco de código, se detectar o padrão `sceMpegIsEnd` (3 instruções específicas), substitui o bloco por um retorno imediato que sinaliza "vídeo terminou" ao game engine.

---

## Estado atual do código

### C++ — já funcional

**`app/src/main/cpp/pcsx2/x86/ix86-32/iR5900.cpp:2245`**

```cpp
// Skip MPEG Game-Fix
static bool skipMPEG_By_Pattern(u32 sPC)
{
    if (!CHECK_SKIPMPEGHACK)
        return 0;

    // sceMpegIsEnd: lw reg, 0x40(a0); jr ra; lw v0, 0(reg)
    if ((s_nEndBlock == sPC + 12) && (memRead32(sPC + 4) == 0x03e00008))
    {
        const u32 code = memRead32(sPC);
        const u32 p1 = 0x8c800040;
        const u32 p2 = 0x8c020000 | (code & 0x1f0000) << 5;
        if ((code & 0xffe0ffff) != p1) return 0;
        if (memRead32(sPC + 8) != p2)  return 0;

        armStore(PTR_CPU(cpuRegs.GPR.n.v0.UL[0]), 1);  // v0 = 1 (FMV ended)
        armStore(PTR_CPU(cpuRegs.GPR.n.v0.UL[1]), 0);
        armLoad(EAX, PTR_CPU(cpuRegs.GPR.n.ra.UL[0]));
        armStore(PTR_CPU(cpuRegs.pc), EAX);             // pc = ra (return)
        iBranchTest();
        g_branch = 1;
        pc = s_nEndBlock;
        return 1;
    }
    return 0;
}
```

Chamado em `iR5900.cpp:2792`:
```cpp
const bool doRecompilation = !skipMPEG_By_Pattern(startpc) && !recSkipTimeoutLoop(...);
```

A função **já usa `armStore`/`armLoad`** em vez das instruções x86 originais (`xMOV`) — já foi adaptada para o ARM64 translation layer. Não há nada a portar no C++.

### Config — já existente

**`app/src/main/cpp/pcsx2/Config.h:1019`**  
```cpp
SkipMPEGHack : 1, // Skips MPEG videos (Katamari and other games need this)
```

**`app/src/main/cpp/pcsx2/Config.h:1432`**  
```cpp
#define CHECK_SKIPMPEGHACK (EmuConfig.Gamefixes.SkipMPEGHack)
```

### GameIndex.yaml — pode ser ativado por jogo

O hack pode ser habilitado por jogo adicionando `- SkipMPEGHack` na lista `gameFixes` de uma entrada. Já está no schema (`gamedb-schema.json:110`). Nenhum jogo usa hoje no nosso GameIndex.yaml (mas muitos usam `SoftwareRendererFMVHack`).

### UI — **AUSENTE** ← ponto de trabalho

Não existe toggle para `SkipMPEGHack` em nenhum arquivo Java ou XML de layout do projeto. O usuário não tem como ativar globalmente — só via GameIndex.yaml por jogo.

---

## O que fazer

### Opção A — Toggle global na SettingsActivity (Recomendado)

Adicionar um `MaterialSwitch` na aba de configurações relevante (provavelmente dentro do card de performance ou fixes) que mapeia para `EmuCore/Gamefixes/SkipMPEG`.

**Arquivos a modificar:**

1. **`app/src/main/res/layout/include_settings_card_performance.xml`** (ou criar `include_settings_card_fixes.xml`)  
   Adicionar `MaterialSwitch` com id `sw_skip_mpeg`.

2. **`app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java`**  
   Adicionar handler igual ao padrão dos outros switches:
   ```java
   MaterialSwitch swSkipMpeg = card.findViewById(R.id.sw_skip_mpeg);
   swSkipMpeg.setChecked(NativeApp.getSetting("EmuCore/Gamefixes", "SkipMPEG", "0").equals("1"));
   swSkipMpeg.setOnCheckedChangeListener((v, checked) ->
       NativeApp.setSetting("EmuCore/Gamefixes", "SkipMPEG", checked ? "1" : "0"));
   ```

3. **`app/src/main/res/values/strings.xml`**  
   Adicionar string:
   ```xml
   <string name="settings_skip_mpeg_hack">Pular vídeos MPEG (FMV Skip)</string>
   ```

### Opção B — Adicionar por jogo no GameIndex.yaml (Baixo custo, impacto limitado)

Para jogos conhecidos que travam em FMVs, adicionar `- SkipMPEGHack` na entrada correspondente do GameIndex.yaml. Não requer mudança de UI mas cobre apenas casos conhecidos.

Jogos candidatos (baseado na documentação do PCSX2):
- Katamari Damacy (`SLUS-20917`)
- We Love Katamari (`SLUS-21239`)
- Katamari series em geral

---

## Esforço estimado

| Abordagem | Esforço | Benefício |
|---|---|---|
| Opção A (toggle UI) | ~1h | Usuário controla; cobre qualquer jogo |
| Opção B (GameDB por jogo) | 30min | Automático para jogos específicos |
| Ambos | ~1.5h | Ideal: GameDB para defaults + UI como escape hatch |

---

## Diferença da abordagem do refresh-experimental

O refresh-experimental tinha que portar `skipMPEG_By_Pattern` para o novo EE recompiler ARM64 nativo que construíram do zero. No nosso projeto, a função já existe e já foi adaptada — o trabalho pendente é apenas a exposição na UI para o usuário poder ativar.
