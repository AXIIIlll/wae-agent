# Handoff Report — Challenger 1 (Adversarial Deduction Challenger)

## 1. Observation
- Baseline review & initial test execution:
  - Initial tests in `BackwardChainingPlannerTest.kt` passed with 405 tests passing under Gradle (`testDebugUnitTest`).
  - However, the baseline suite only tested happy paths and basic variations.
- Adversarial test harness implementation:
  - Created `app/src/test/java/com/example/newandroidapp/stability/BackwardChainingAdversarialStressTest.kt` covering the 6 mandatory prompts from dispatch, full 5-level backward chaining state transitions, quote wrapping, strange spacings, mixed Chinese/English, and trailing punctuation.
- Execution command:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
  .\gradlew.bat testDebugUnitTest --tests com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest --console=plain
  ```
- Verbatim failures observed:
  1. **Failure 1** on mandatory prompt `"去spotify點一首歌名為晴天"`:
     ```
     com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest > testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian FAILED
         org.junit.ComparisonFailure: expected:<[]晴天> but was:<[點一首歌名為]晴天>
         at org.junit.Assert.assertEquals(Assert.java:117)
         at org.junit.Assert.assertEquals(Assert.java:146)
         at com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest.testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian(BackwardChainingAdversarialStressTest.kt:127)
     ```
     `extractTerminalGoalSpec` extracted:
     `appHint = "Spotify"`, `terminalVerb = TerminalVerb.PLAY`, `targetEntity = "點一首歌名為晴天"`.
  2. **Failure 2** on quote-wrapped sentences (`"「去 Spotify 買茶歌 『struct』！」"`):
     ```
     com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest > testAdversarialPunctuationAndLeadingQuotes FAILED
         java.lang.AssertionError: expected:<Spotify> but was:<null>
         at org.junit.Assert.fail(Assert.java:89)
         at org.junit.Assert.failNotEquals(Assert.java:835)
         at org.junit.Assert.assertEquals(Assert.java:120)
         at org.junit.Assert.assertEquals(Assert.java:146)
         at com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest.testAdversarialPunctuationAndLeadingQuotes(BackwardChainingAdversarialStressTest.kt:135)
     ```
     `extractTerminalGoalSpec` extracted:
     `appHint = null`, `targetEntity = "去 Spotify 買茶歌 『struct』！"`.
  3. **Observation 3** in `BackwardChainingPlanner.kt` lines 97-100:
     `PLAY_VERB_PREFIXES` contains `"買茶歌", "茶歌", "查歌", "找歌", "聽歌", "放歌", "點歌", "搜歌", "點播", "播放", "放一首", "播一首", "聽一首", "查一首", "找一首", "聽", "放", "播"`.
     `"點一首"`, `"點一首歌"`, and `"點"` are missing.
  4. **Observation 4** in `BackwardChainingPlanner.kt` lines 274-279:
     Quotes are only stripped at Step 6 from `working` via `removeSurrounding`, after Step 2 (`stripLeadingOralPrefixes`), Step 3 (`KNOWN_APPS`), and Step 4 (verb prefixes) have already executed.
  5. **Observation 5** in `InAppOperationPlanner.kt` lines 538-541:
     `if (clean.contains("然後") || clean.contains("接著") || clean.contains("再來") || clean.contains("之後")) return "" to ""` aborts before delegating to `BackwardChainingPlanner`.

## 2. Logic Chain
1. From Observation 1 & 3: In the prompt `"去spotify點一首歌名為晴天"`, after stripping `"去"` and `"spotify"`, `working` is `"點一首歌名為晴天"`. Because `"點一首"` and `"點"` are missing from `PLAY_VERB_PREFIXES`, `working` retains `"點"`. In Step 5, `CARRIER_PREFIXES` (`"一首歌名為"`, etc.) cannot match because the string starts with `"點"`. As a result, `targetEntity` becomes `"點一首歌名為晴天"`.
2. This creates a severe cascading failure across the entire pipeline:
   - Level 2 inputs `"點一首歌名為晴天"` into the Spotify search box, yielding zero results.
   - Level 0 cannot match the actual song node `"晴天"`.
   - `ClosedLoopVerifier` with `isExpandingSearchEntry = true` checks if the screen contains `"點一首歌名為晴天"`, which never happens, causing the system to hang or fail verification.
3. From Observation 2 & 4: In `"「去 Spotify 買茶歌 『struct』！」"`, because quotes are not stripped at the start of `extractTerminalGoalSpec`, `working` begins with `"「"`. Consequently, `stripLeadingOralPrefixes` fails to match `"去"`, and `KNOWN_APPS` fails to match `"spotify"`, resulting in `appHint = null` and complete breakdown of the goal deduction.
4. From Observation 5: Multi-step connectors like `"然後"` in `InAppOperationPlanner` abort the query before `BackwardChainingPlanner` can isolate the follow-up action.

## 3. Caveats
- The 5-level backward chaining state machine logic itself (Level 0 direct target, Level 3B bottom tab, Level 3A top fake box, Level 2 real input box, Level 1 result list, and Level 4 fallback) passed 100% of the transition stress tests when given clean entity inputs.
- The strict voice search / microphone button exclusion (`isForbiddenVoiceOrCameraNode`) passed 100% across all adversarial button placements.
- Zero emojis and zero third-party library rules remain 100% compliant.

## 4. Conclusion
- **VERDICT: REQUEST_CHANGES**
- The core engine has 2 blocking bugs in entity extraction that fail mandatory requirements:
  1. Mandatory prompt `"去spotify點一首歌名為晴天"` extracts `"點一首歌名為晴天"` instead of `"晴天"`.
  2. Quote-wrapped inputs (`"「去 Spotify 買茶歌 『struct』！」"`) fail app resolution (`appHint = null`).
- Remediation needed in `BackwardChainingPlanner.kt`:
  1. Add `"點一首歌"`, `"點一首"`, `"點"` to `PLAY_VERB_PREFIXES`, and expand `CARRIER_PREFIXES` if needed.
  2. Strip quotes (`「」`, `『』`, `""`, `''`) at the very start of `extractTerminalGoalSpec` before prefix and app matching.
  3. Strip trailing punctuation (`.`, `!`, `?`, `！`, `？`, `。`, `…`) from `cleanTarget`.
  4. (Optional) Harmonize `InAppOperationPlanner` line 539 so it does not prematurely abort when `BackwardChainingPlanner` can handle connectors.

## 5. Verification Method
Run the reproducible stress test suite via PowerShell:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\User\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest --tests com.example.newandroidapp.stability.BackwardChainingAdversarialStressTest --console=plain
```
**Invalidation Condition**: Both `testRequiredPrompt6_qu_spotify_dian_yi_shou_ge_ming_wei_qingtian` and `testAdversarialPunctuationAndLeadingQuotes` must pass, and the entire test suite must achieve 100% pass (0 failures).
