# VideoSlim External-Review Hardening Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task. Every code-producing task follows RED → GREEN → focused regression → exact-revision review.

**Goal:** Implement every accepted external-review improvement on top of `ad676b53b98741645ad6afb273d86705afc17b13` without changing the accepted media product contract.

**Architecture:** Preserve the existing single foreground-service/runtime design, synchronous recovery durability, Media3 main-looper control plane, ARM64 release scope, and VBR video policy. Move blocking storage/provider/log work behind bounded single-writer executors, make service-owned task/terminal state authoritative, strengthen AAC payload evidence with per-sample streaming digests, progressively constrain Flutter state, and add repository CI plus Android framework tests.

**Tech Stack:** Flutter 3.44.6 / Dart 3.12, Kotlin/JVM 17, Android API 26–36, Media3 1.10.1, JUnit 4, AndroidX Test, GitHub Actions.

**Explicit non-goals:** production signing; changing VBR to CBR; changing release ABI away from `arm64-v8a`; a big-bang HomeScreen rewrite; a two-phase engine start API; claiming Pixel/OEM acceptance without a connected device.

---

### Task 1: Establish repository CI and explicit uncaught-error policy

**Objective:** Turn the existing deterministic gates into GitHub PR checks and stop globally marking every platform error handled.

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `lib/main.dart`
- Create: `test/startup_error_policy_test.dart`

**Steps:**
1. Add a pure `StartupErrorPolicy`/callback helper whose debug/profile behavior returns `false` after best-effort logging and whose release behavior returns `true` only for explicitly classified recovered errors; unknown errors return `false`.
2. Add RED tests for unknown errors, logger synchronous failure, logger asynchronous failure, and an explicitly recovered error.
3. Integrate the helper into `installStartupErrorHooks()` while preserving `FlutterError.presentError()`.
4. Add CI jobs pinned to Flutter 3.44.6 and JDK 17. Run format check, analyze, Flutter tests, Android JVM tests, debug/release lint, and debug/release assembly serially.
5. Run focused Dart tests, then format/analyze/full Flutter tests.

### Task 2: Replace synchronous multi-instance logging with a process-wide bounded single writer

**Objective:** Keep F19 semantics while removing file append/read/rotation from Android platform/main threads and preventing independent stores from racing on the same file.

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/AppLogStore.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AppLogDispatcher.kt`
- Modify: `VideoSlimApplication.kt`, `MainActivity.kt`, `ProcessingService.kt`, `LogChannel.kt`, `EngineChannel.kt`
- Create/modify tests under `android/app/src/test/kotlin/com/videoslim/videoslim/`

**Steps:**
1. Add pure RED tests for bounded queue behavior: coalesce progress, retain error/terminal/recovery entries, preserve order, and enforce one writer.
2. Implement one application-scoped dispatcher with a bounded queue and a single executor. Normal progress entries coalesce by task; priority entries are never evicted by progress.
3. Make append asynchronous. Move read/share snapshot to the same executor and post MethodChannel results/Activity launch back to main.
4. Ensure shutdown/drain is deterministic for tests and application teardown without blocking UI.
5. Run logger/channel-focused tests and Android JVM suite.

### Task 3: Move recovery reconciliation and durable journal mutations off lifecycle/main threads

**Objective:** Call foreground notification first, execute reconciliation once per process, preserve synchronous `commit()` durability, and never block the Service/Flutter main thread on journal I/O.

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/RecoveryIoCoordinator.kt`
- Modify: `VideoSlimApplication.kt`, `ProcessingService.kt`, `TranscodeEngine.kt`, `AudioExtractionEngine.kt`, `TaskRecoveryStore.kt`
- Add focused JVM tests.

**Steps:**
1. Add RED tests for one-shot reconciliation, ordered journal operations, failure propagation, and no duplicate cleanup.
2. Start one process-level reconciliation future from Application on a single I/O executor; do not synchronously wait in Application.
3. In Service, call `startForegroundCompat()` before awaiting reconciliation. Continue task launch only after reconciliation succeeds; fail terminally if it fails.
4. Move engine `begin()` and stage commits into existing I/O preparation paths before media work; post resulting progress/failure to main. Do not weaken durability with `apply()`.
5. Move terminal recovery clear/delete work to I/O and emit terminal state only after the ownership boundary completes.
6. Keep Media3 construction/start/cancel/dispose on the main looper.

### Task 4: Move ContentResolver and SAF work off Flutter/Service main threads

**Objective:** Preserve early validation and final publication validation while moving provider Binder/disk calls to bounded executors.

**Files:**
- Modify: `EngineChannel.kt`, `VideoPickerChannel.kt`, `MediaActionsChannel.kt`, `LogChannel.kt`, `MediaStoreSaver.kt`
- Add focused JVM/policy tests and Android framework tests where pure fakes are insufficient.

**Steps:**
1. Add delayed-provider tests proving MethodChannel state remains single-flight and results return once on main.
2. Run EngineChannel destination validation on an I/O executor, then resume permission/request launch on main.
3. Run picker output-location query/persistence and media MIME/delete/provider operations off main; keep Activity Result and `startActivity()` calls on main.
4. Retain publication-time `validateOutputDestination()` as the final authority.
5. Verify cancellation/disposal suppresses stale callbacks and releases pending result state.

### Task 5: Make service task ownership and termination authoritative and idempotent

**Objective:** Remove registry-to-service reverse inference and guarantee local cleanup even when registry/journal updates fail.

**Files:**
- Modify: `ProcessingService.kt`
- Create/modify: `ProcessingServiceState.kt` and focused tests.

**Steps:**
1. Add RED tests for immutable task kind routing, cancellation before engine ID, missing/mismatched registry, repeated terminal calls, notification failure, recovery failure, timeout, and destroy.
2. Introduce `ActiveTaskContext(publicTaskId, taskKind, engineTaskId, lastStartId)` with one terminal gate.
3. Route cancel/timeout/dispose from the context; remove `else -> video` fallback.
4. Implement `finishOnce()` so registry and recovery updates are best effort but engine cancellation/disposal, WakeLock release, foreground removal, terminal notification attempt, and `stopSelfResult()` cannot be skipped.
5. Preserve exactly-one terminal snapshot/notification and stale-task rejection.

### Task 6: Add per-sample AAC payload digests

**Objective:** Strengthen the existing aggregate contract to detect equal-length payload substitution without hashing MP4 container bytes.

**Files:**
- Modify: `AudioSampleCopyLoop.kt`, `AudioMetadataReader.kt`, `AudioExtractionEngine.kt`
- Modify tests: `AudioSampleCopyLoopTest.kt`, `AudioMetadataScanPolicyTest.kt`, `AudioOutputVerifierTest.kt`

**Steps:**
1. Add RED tests where sample count and lengths match but one payload byte differs.
2. Compute streaming SHA-256 over `version || sample-index || sample-length || sample-payload` during source pre-scan, actual copy, and output rescan; keep memory O(1) and reuse existing buffers.
3. Compare all three digests in `requireLosslessPayloadAggregateIntegrity()` and retain existing count/bytes/indexed/timeline/profile checks.
4. Keep normalized PTS/flags in their existing independent verifier contract; do not hash whole `.m4a` files.
5. Run focused and full Android JVM tests.

### Task 7: Constrain Flutter workflow state incrementally

**Objective:** Make mutually exclusive workflow dimensions typed and state mutations named without breaking legitimate ownership/cancellation overlaps or rewriting HomeScreen wholesale.

**Files:**
- Modify: `lib/state/home_flow_state.dart`, `lib/screens/home_screen.dart`
- Create/modify tests under `test/state/` and `test/widget_test.dart`

**Steps:**
1. Add RED invariant tests for impossible interaction/task combinations while preserving tested combinations such as processing + restoring + ownership uncertain and processing + cancelling.
2. Introduce typed interaction and task-lifecycle phases; keep ownership uncertainty, stream closure, publication, task kind, and native task phase as explicit orthogonal facts.
3. Make mutable storage private and expose named transition methods. Derive compatibility getters used by widgets during migration.
4. Move import/preflight and reconnect/task transition mutations behind the state API; do not introduce another state-management framework.
5. Run focused race/widget tests, then full Flutter tests.

### Task 8: Add Android instrumentation coverage and an isolated x86_64 test variant

**Objective:** Exercise real framework MediaExtractor/MediaMuxer/provider/log seams without changing the release ABI.

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/androidTest/kotlin/com/videoslim/videoslim/*`
- Create: a small audited AAC fixture under `android/app/src/androidTest/assets/`
- Modify: `.github/workflows/ci.yml`

**Steps:**
1. Add AndroidX instrumentation runner/dependencies and a debug/test-only x86_64 ABI path; release remains exactly `arm64-v8a`.
2. Generate and document a tiny AAC-LC fixture.
3. Test real `MediaExtractor → MediaMuxer → MediaExtractor` payload digest, length, flags, normalized PTS, single-audio/no-video tracks, and malformed/truncated rejection where framework behavior permits.
4. Add LogChannel/provider thread smoke coverage.
5. Compile instrumentation tests locally; run them in an emulator GitHub Actions job. Keep Pixel/OEM checks explicitly separate.

### Task 9: Integrate, document, version, review, and publish

**Objective:** Produce one exact reviewed source revision and invalidate old release evidence truthfully.

**Files:**
- Modify: `README.md`, `docs/VideoSlim PRD.md`, `docs/m3-device-acceptance.md`, `pubspec.yaml`
- Update generated lock/build metadata only as required.

**Steps:**
1. Document async runtime ownership, digest semantics, CI/instrumentation scope, release ARM64 policy, and remaining Pixel gate.
2. Bump version/build because the prior APK and exact-SHA reviews no longer apply.
3. Run serialized full gates: format, analyze, Flutter tests, Android JVM tests, lintDebug/lintRelease, assembleDebug/assembleRelease, assembleDebugAndroidTest, `git diff --check`.
4. Inspect merged manifest, permissions, ABI, signature, VCS revision, APK hash, and source bundle at the final immutable commit.
5. Run three independent exact-SHA read-only reviews covering runtime/termination, audio/framework integrity, and Flutter/CI/state.
6. Push branch, open PR, verify GitHub Actions, fix every valid blocker on a new SHA, rerun affected gates/reviews, merge to `main`, and verify remote `main` equals the final commit.
7. Do not claim physical-device/P0 production-signing acceptance; archive those as explicit remaining gates.
