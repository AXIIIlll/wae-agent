# DISPATCH — Worker M1 (Data Models, Conversation Manager & Local Persistence)

## Mission
Implement Milestone M1 (Features F1, F2, F3) of the Android Conversational AI Assistant at E:\NewAndroidApp.

## Mandatory Reading
- E:\NewAndroidApp\.agents\teamwork\ORIGINAL_REQUEST.md (Authoritative requirements)
- E:\NewAndroidApp\PROJECT.md (Project architecture and interface contracts)
- E:\NewAndroidApp\.agents\teamwork\explorer_survey_1\survey_report.md (Data models & UI specs)
- E:\NewAndroidApp\.agents\teamwork\explorer_survey_2\survey_report.md (Engine & persistence specs)

## Scope & File Ownership
You exclusively own:
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\chat\ChatMessage.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\chat\ConversationManager.kt`
- `E:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\PlayStoreComplianceHelper.kt` (only wiring the wipe call)

## Key Technical Requirements
1. Implement `ChatMessage.kt` in package `com.example.newandroidapp.chat`:
   - `enum class ChatRole { USER, AI }`
   - `data class ScreenCandidateItem(...)`
   - `data class ConversationTurnContext(...)`
   - `data class ChatMessage(...)`
2. Implement `ConversationManager.kt` in package `com.example.newandroidapp.chat`:
   - Thread-safe singleton repository.
   - Observable pattern with `ConversationObserver` interface (`onMessagesUpdated`, `onTurnContextUpdated`).
   - Dual-sync methods: `appendUserMessage`, `appendAiMessage`, `updateTurnContext`, `clearConversation`, `getMessages`, `getTurnContext`.
   - Local persistence to `filesDir/conversation_history.json`:
     - Load on `initialize(context)`.
     - Save atomically on message append and clear.
     - Robust against malformed/empty files.
3. Wire `PlayStoreComplianceHelper.kt`:
   - Ensure consent revocation/data wipe invokes `ConversationManager.clearConversation()`.
4. Strictly ZERO EMOJIS in any string, comment, or output.
5. Verify your implementation by running `./gradlew.bat compileDebugKotlin` to ensure 0 errors and 0 warnings.
6. Write your completion report in `E:\NewAndroidApp\.agents\teamwork\worker_m1\handoff.md` and notify orchestrator.

## Mandatory Warning
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.


## 2026-11-01T06:36:50Z
You are Worker M1. Your working directory is E:\NewAndroidApp\.agents\teamwork\worker_m1.
You MUST read E:\NewAndroidApp\.agents\teamwork\ORIGINAL_REQUEST.md before starting work.
Also read E:\NewAndroidApp\PROJECT.md and E:\NewAndroidApp\.agents\teamwork\worker_m1\DISPATCH.md.

Your objective:
Implement Milestone M1 (Data Models, Conversation Manager & Local Persistence):
1. Create ChatMessage.kt in com.example.newandroidapp.chat with ChatRole, ScreenCandidateItem, ConversationTurnContext, and ChatMessage.
2. Create ConversationManager.kt in com.example.newandroidapp.chat with thread-safe singleton state, observable listeners, local filesDir JSON persistence, and clearConversation().
3. Wire PlayStoreComplianceHelper.kt consent revocation to call ConversationManager.clearConversation().
4. Ensure strictly zero emojis.
5. Verify build with `./gradlew.bat compileDebugKotlin`.
6. Write handoff report in E:\NewAndroidApp\.agents\teamwork\worker_m1\handoff.md and notify orchestrator.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.
