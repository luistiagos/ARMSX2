# Bug: Allowlist de Vulkan Android — premissa original REFUTADA, mas Xclipse está mal classificado

> ## ⚠️ REVISÃO 2026-08-10 — a tese original deste bug estava errada
>
> A auditoria do upstream (`ARMSX2/ARMSX2` master) derrubou a premissa. **Não** devemos liberar
> Vulkan em Mali. Resumo:
>
> 1. **O allowlist é nosso, não herdado.** `git log -S IsAllowlistedAndroidVulkanGPU` aponta o
>    commit local `1d2379bf` ("telimetry and fixes", 2026-07-06). O upstream não tem allowlist —
>    `IsSuitableDefaultRenderer()` lá é o do PCSX2 vanilla, sem bloco `__ANDROID__`.
> 2. **O motivo do upstream mandar Mali para OpenGL é framebuffer fetch, não qualidade de driver.**
>    De `pcsx2/GS/GSUtil.cpp` no master:
>    > *"Android: Auto resolves to Vulkan HW on Adreno (the tile-memory framebuffer-fetch fast
>    > path), OpenGL HW elsewhere (Mali runs GL_ARM_shader_framebuffer_fetch; Xclipse has no
>    > working VK fbfetch)."*
> 3. **Confirmado no nosso próprio código:**
>    | | fbfetch | Como |
>    |---|---|---|
>    | Mali + OpenGL | ✅ | `GLAD_GL_ARM_shader_framebuffer_fetch` ([GSDeviceOGL.cpp:783](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L783)) |
>    | Mali + Vulkan | ❌ | exige `VK_EXT_rasterization_order_attachment_access` ([GSDeviceVK.cpp:2733](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2733)), que a ARM não expõe |
>
>    O blending preciso do PCSX2 depende de fbfetch. **Mover Mali para Vulkan pioraria os glitches.**
>    O OpenGL em Mali não é o caminho degradado — é o caminho com blending correto.
>
> **O bug que sobra é o inverso e é real:** nosso allowlist aprova **Xclipse** para Vulkan
> ([GSDeviceVK.cpp:2067](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2067)),
> e o upstream afirma que Xclipse **não tem VK fbfetch funcional**. Se estiver certo, estamos
> mandando todo Galaxy com Xclipse para o renderer errado.
>
> Ver "Próximos passos" reescrito no fim. O texto abaixo é o diagnóstico original, mantido para
> registro do raciocínio.

---

# (original) Allowlist de Vulkan automático rejeita Mali moderno — Redmi MediaTek cai para OpenGL ES

- **Detectado em:** 2026-08-10 16:02 (auditoria de código, motivada por relatos de lentidão +
  glitches gráficos em Redmi)
- **Origem:** auditoria de `GSDeviceVK::IsSuitableDefaultRenderer` / `IsAllowlistedAndroidVulkanGPU`
- **Errors (serviço):** nenhum — não é crash, não gera telemetria.
- **Classe:** fail (performance + correção gráfica)
- **Reincidência:** sistêmico — afeta todo aparelho não-Adreno/não-Xclipse que não mexeu nas
  configurações

## Sintoma

Lentidão acentuada **com glitches gráficos** (bloom desalinhado, linhas verticais na água,
artefatos de blending) em Redmi/Poco com SoC MediaTek. A combinação "lento **e** com glitch" é a
pista: renderer diferente, não só GPU fraca.

## Causa raiz (CONFIRMADA no código)

`GSUtil::GetPreferredRenderer()` no Android só escolhe Vulkan se
`GSDeviceVK::IsSuitableDefaultRenderer()` aprovar; senão cai para OpenGL
([GSUtil.cpp:216-228](../../../app/src/main/cpp/pcsx2/GS/GSUtil.cpp#L216-L228)).

E a aprovação passa por um allowlist bem estreito
([GSDeviceVK.cpp:2064-2091](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2064-L2091)):

```cpp
// Samsung Xclipse (RDNA2/3-based) ships with solid Vulkan drivers.
if (name.find("Xclipse") != std::string::npos)
    return true;

const size_t adreno_pos = name.find("Adreno");
if (adreno_pos == std::string::npos)
    return false;                     // <-- Mali / PowerVR / IMG param aqui
...
return (model >= 600);
```

O comentário acima da função é explícito sobre a intenção:

> *"Android driver quality varies too much for a blanket Vulkan default. Only GPU families with
> proven conformant drivers qualify; everything else (Mali, PowerVR, IMG, older Adreno) stays on
> OpenGL unless the user picks Vulkan explicitly in settings."*

O problema é que o allowlist envelheceu. Ele reprova:

| GPU | SoC | Aparelhos |
|---|---|---|
| Mali-G57 | Helio G99 | Redmi Note 12/13 4G, Poco M5/M6 |
| Mali-G68 | Dimensity 920/1080 | Redmi Note 11T/12 Pro+ |
| **Mali-G610** | **Dimensity 7200/8020** | **Redmi Note 13 Pro, Poco X6** |
| Mali-G615 | Dimensity 8200 | Poco F5/X6 Pro |

Mali-G610/G615 (Valhall 4ª gen) têm driver Vulkan competente hoje — são a base de vários
emuladores móveis. Estão sendo empurrados para OpenGL ES sem necessidade.

E o custo do OpenGL ES aqui não é só velocidade: o GS do PCSX2 depende de framebuffer fetch para
blending preciso, e o path GL móvel emula isso de forma incompleta. Daí os glitches virem junto com
a lentidão, em vez de só lentidão.

Vale notar que o fork **já tem** tratamento de Mali em runtime — `RuntimeGpuProfile::Mali`
([GSGPUProfile.cpp:174-185](../../../app/src/main/cpp/pcsx2/GS/Renderers/Common/GSGPUProfile.cpp#L174-L185)),
usado em [GSRendererHW.cpp:5521](../../../app/src/main/cpp/pcsx2/GS/Renderers/HW/GSRendererHW.cpp#L5521)
e nos defines de shader
([GSDeviceOGL.cpp:1477](../../../app/src/main/cpp/pcsx2/GS/Renderers/OpenGL/GSDeviceOGL.cpp#L1477)) —
e o path Vulkan sabe setar esse profile
([GSDeviceVK.cpp:2662](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2662)).
Ou seja: a infra para rodar Mali em Vulkan existe; só o allowlist não deixa chegar lá.

## Como reproduzir

1. Redmi Note 13 Pro (Dimensity 7200, Mali-G610) com configurações de fábrica.
2. Rodar qualquer jogo e conferir no log: `"GPU '...' is not allowlisted for automatic Vulkan;
   using OpenGL."` ([GSDeviceVK.cpp:2124](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2124))
3. Trocar o renderer para Vulkan manualmente nas configurações e comparar FPS + artefatos.

## Próximos passos (REESCRITOS após a revisão de 2026-08-10)

1. ~~Ampliar o allowlist para aceitar Mali moderno.~~ **Cancelado** — ver a caixa de revisão no
   topo. Mali sem VK fbfetch renderiza blending pior em Vulkan que em OpenGL. Manter Mali no GL.
2. **Investigar o Xclipse**, que é o bug real que sobrou. Confirmar se
   `VK_EXT_rasterization_order_attachment_access` está ausente nos drivers Xclipse (Galaxy S22+,
   S23 FE, A55…). Se estiver, remover o `if (name.find("Xclipse") != npos) return true;` de
   [GSDeviceVK.cpp:2067](../../../app/src/main/cpp/pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp#L2067) —
   estamos mandando esses aparelhos para o renderer errado hoje.
3. **Trocar o critério de "família de GPU" por "fbfetch disponível"**, que é a propriedade que de
   fato importa. Um allowlist por nome envelhece; uma checagem de extensão, não. O upstream faz
   isso de fora (`g_gs_android_prefer_vk`, setado a partir da string `GL_RENDERER` antes do GS
   subir); dá para fazer melhor de dentro, consultando a extensão no enumerate.
4. Reavaliar o issue upstream [#513](https://github.com/ARMSX2/ARMSX2/issues/513) (God of War,
   fontes e efeitos quebrados em **Mali-G615 + Vulkan**, mantenedor "I'm looking into this" em
   2026-08-09). É a evidência de campo de que Mali+Vulkan quebra blending — exatamente o que este
   bug propunha causar de propósito. Acompanhar o desfecho.
5. Avaliar apontar usuários de Adreno para os drivers Turnip via `GpuDriverManagerActivity` — o
   allowlist já reconhece nomes `"Turnip Adreno (TM) 650"`. **Este item continua válido.**

## Referências upstream (auditoria 2026-08-10)

- `pcsx2/GS/GSUtil.cpp` @ master — `GetPreferredRenderer()` com `g_gs_android_prefer_vk` e o
  comentário que explica a decisão por fbfetch.
- `pcsx2/GS/Renderers/Vulkan/GSDeviceVK.cpp` @ master — `IsSuitableDefaultRenderer()` sem nenhum
  bloco `__ANDROID__`.
- [#533](https://github.com/ARMSX2/ARMSX2/issues/533) "On Mali devices, the Vulkan driver doesn't
  seem to be optimal" — fechado sem investigação ("not enough detail to act on this").
- [#331](https://github.com/ARMSX2/ARMSX2/issues/331) GT4 texto de menu faltando (Mali),
  [#339](https://github.com/ARMSX2/ARMSX2/issues/339) SotC regressão de performance em Mali,
  [#232](https://github.com/ARMSX2/ARMSX2/issues/232) Sly Cooper 2 travando em **Mali + Vulkan**.

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.
