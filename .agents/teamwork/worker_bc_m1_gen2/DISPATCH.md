## 2026-11-03T09:01:59Z
You are Worker BC M1 Gen2 (Remediation & Hardening Specialist).
Your working directory is: e:\NewAndroidApp\.agents\teamwork\worker_bc_m1_gen2
Your parent is orchestrator: 007ede6b-1923-4e46-b109-ad7dd568a12c

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY INPUTS TO READ FIRST:
1. e:\NewAndroidApp\.agents\teamwork\ORIGINAL_REQUEST.md
2. e:\NewAndroidApp\PROJECT.md
3. e:\NewAndroidApp\.agents\teamwork\reviewer_bc_1\handoff.md
4. e:\NewAndroidApp\.agents\teamwork\reviewer_bc_2\handoff.md
5. e:\NewAndroidApp\.agents\teamwork\challenger_bc_1\handoff.md
6. e:\NewAndroidApp\.agents\teamwork\challenger_bc_2\handoff.md

SPECIFIC REMEDIATION TASKS:
1. In e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\BackwardChainingPlanner.kt:
   - At the beginning of `extractTerminalGoalSpec`:
     Strip enclosing quotation marks and brackets (`「...」`, `"..."`, `『...』`, `'...'`, etc.) before any prefix, app, or verb parsing. Also remove trailing punctuation before final return.
   - In `PLAY_VERB_PREFIXES`:
     Add `"點一首歌"`, `"點一首"`, `"點首歌"`, `"點"`.
   - In `CARRIER_PREFIXES`:
     Add `"一首叫"`, `"一首名為"`, `"一首叫做"`.
   - When cleaning `cleanTarget`:
     Strip trailing punctuation and spaces (`Regex("[！。，？?!\.、…\\s]+$")`).
2. In e:\NewAndroidApp\app\src\main\java\com\example\newandroidapp\stability\InAppOperationPlanner.kt:
   - In `FORBIDDEN_VOICE_AND_CAMERA_KEYWORDS`:
     Add `"mic"`, `"speech"`, `"asr"`, `"audio input"`, `"audio_input"`, `"camera search"`, `"lens"` to prevent voice ban bypass on elements with generic IDs and compound labels (e.g. `label="mic 搜尋"`).
3. In test files (e:\NewAndroidApp\app\src\test\java\com\example\newandroidapp\stability\BackwardChainingAdversarialStressTest.kt and BackwardChainingPlannerTest.kt):
   - Resolve any duplicate method declarations or syntax issues.
   - Ensure all adversarial and regression test cases compile and pass cleanly.
4. Run verification commands in PowerShell:
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
   .\gradlew.bat testDebugUnitTest --console=plain
   .\gradlew.bat assembleRelease --console=plain
   Verify 100% test pass rate with 0 failures, and clean release APK build.

CONSTRAINTS:
- 100% self-developed code (no third-party AI/CV/OCR/JSON libraries).
- Strict voice input ban (never click microphone/voice buttons).
- Zero emoji compliance across all code, logs, and comments.
- Maintain progress.md in your working directory.
- Write handoff.md in your working directory when finished.
- Report completion back to parent orchestrator (007ede6b-1923-4e46-b109-ad7dd568a12c) via send_message.
