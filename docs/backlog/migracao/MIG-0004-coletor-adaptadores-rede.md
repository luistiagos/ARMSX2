# MIG-0004: Coletor de Adaptadores de Rede para Telemetria (NetworkAdapterCollector)

- **Prioridade:** Baixa / Média (Diagnóstico de Conectividade)
- **Status:** Aberto
- **Origem:** `version1` (`app/src/main/java/kr/co/iefriends/pcsx2/utils/NetworkAdapterCollector.java` — 285 linhas)
- **Documento de referência:** [`docs/plano-fork-sobre-upstream.md`](../../plano-fork-sobre-upstream.md) §4.5

---

## 1. Contexto e Objetivo

Na `version1`, a classe `NetworkAdapterCollector` coletava informações sobre a conectividade do dispositivo (interfaces Wi-Fi, redes móveis, MTU, status de IPv6 e rotas) para enriquecer os relatos de erro e entender falhas de download e conectividade no suporte técnico.

---

## 2. Análise Técnica

- O arquivo `NetworkAdapterCollector.java` era autocontido e realizava apenas consultas ao `ConnectivityManager` e `NetworkInterface`.
- Pode ser portado diretamente para `com.armsx2.telemetry` no fork.
- O payload gerado pode ser anexado aos eventos enviados pelo `TelemetryReporter`.

---

## 3. Escopo da Implementação

**Arquivos a criar/modificar:**
- `platforms/android/app/src/main/java/com/armsx2/telemetry/NetworkAdapterCollector.java` (ou `.kt`)
- `platforms/android/app/src/main/java/com/armsx2/telemetry/TelemetryReporter.java` (anexar informações de rede no payload)

---

## 4. Como Validar

1. Provocar um relatório de telemetria ou log de erro.
2. Inspecionar o JSON enviado ao endpoint `/logErr` e confirmar que o campo com o resumo dos adaptadores de rede está devidamente preenchido.
