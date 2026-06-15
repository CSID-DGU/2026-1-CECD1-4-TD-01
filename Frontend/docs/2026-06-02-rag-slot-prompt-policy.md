# 2026-06-02 RAG Slot And Prompt Policy

## Decision

The app separates prompt inputs into three layers.

1. Fixed policy instruction
   - Always injected at model initialization.
   - User cannot edit it directly.
   - Contains counseling safety, crisis handling, dynamic-analysis interpretation rules, and WHO 5-17 physical activity guidance.

2. User custom response instruction
   - User-editable.
   - Used only for tone, answer length, formatting preference, and response style.
   - Legacy full system prompts are normalized back to the default custom instruction so fixed safety policy is not overwritten.

3. Dynamic context
   - Injected into the latest user message only when relevant.
   - Memory/RAG search, Health, phenotype, gallery analysis, and current voice emotion are all marked as auxiliary context.
   - Current user text always has priority over stale or conflicting analysis.

## RAG Slot Strategy

Longer-lived analysis documents are stored as replaceable RAG slots under app private storage:

- `Health`: Health Connect summary.
- `Phenotype`: call/app usage 생활 패턴 summary.
- `Gallery`: gallery analysis summary.

Each refresh replaces the existing slot document. This keeps the prompt source current without growing unbounded context.

The slot files live in:

```text
filesDir/rag_slots/
```

The runtime still injects only the enabled/relevant slot into the latest user turn. The stored slot is the canonical cached document for that analysis type.

## Voice Emotion Strategy

Voice emotion is not a RAG slot.

Reason:

- It changes per utterance.
- It is only useful for the current response tone.
- Storing it as long-term context can cause stale emotional assumptions in later turns.

Therefore voice emotion analysis is injected directly into the current user prompt:

```text
[음성 감정 분석]
현재 녹음의 주요 감정: ...
[응답 조정 지침]
...
```

The original audio file is still passed to Gemma/LiteRT-LM as an audio attachment when direct attachment mode is enabled.

## WHO Guidance Placement

WHO youth physical activity guidance belongs in fixed policy, not in every Health prompt.

The fixed policy says:

- 5-17 year olds should average at least 60 minutes/day of moderate-to-vigorous physical activity.
- Include vigorous aerobic activity at least 3 days/week.
- Include muscle and bone strengthening activity at least 3 days/week.
- Reduce sedentary behavior and recreational screen time.
- Start small and increase frequency, intensity, and duration gradually.
- Use activity patterns as auxiliary counseling context, not diagnosis.

Health and phenotype RAG slots only include short reminders to interpret their data through the fixed policy when lifestyle improvement is relevant.

## Current Implementation

- `RagSlotStore.kt`
  - Defines `RagSlot`.
  - Stores replaceable JSON slot documents.

- `HealthData.kt`
  - `refreshHealthRagSlot()` reads Health Connect and replaces the Health slot.

- `PhenotypePrompt.kt`
  - `refreshPhenotypeRagSlot()` analyzes call/app usage and replaces the Phenotype slot.

- `GalleryAnalysisCache.kt` / `GalleryAnalysisPrompt.kt`
  - Gallery analysis cache also replaces the Gallery slot.

- `ChatScreen.kt`
  - Health/Phenotype/Gallery context flows through slot refresh paths.
  - Voice emotion remains current-turn prompt context only.
