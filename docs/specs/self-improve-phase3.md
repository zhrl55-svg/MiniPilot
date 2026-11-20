# Self-improve loop — Phase 3: product fixes the cards demanded

Status: **landed in source, installed and verified on the device** (2026-11-20,
build 58). Phase 1/2 are in `self-improve-loop.md` / `self-improve-phase2.md`.

## Why Phase 3 is code, not prompt

Phase 2 ran the full loop on the 2026-11-20 cards: 9 cards → 9 clusters → 3
rule candidates → **all `no-diff`**. The rule layer was already saturated
(SOUL.md requires evidence-based verification and re-reading), so the A/B gate
correctly refused redundant rules. What the cards actually pointed at was seven
product/documentation defects. This phase fixes them.

## The seven fixes

| # | Card | Fix | Where |
|---|---|---|---|
| 1 | `android-speak` answers `speaking` before anything is heard | `--wait` blocks until the engine reports done/error; `status: completed` vs `unconfirmed` + reason | `SpeakOffloadHandler.kt`, `TextToSpeechManager.speakAndAwait` |
| 2 | `scheduled_task` stored `", ,检查壁挂炉水压,"` | restore CJK punctuation between CJK chars; detect empty fragments and return a warning + `stored_prompt` so the agent re-creates the task | `LocalCapabilityExecutor.kt`, rule "Scheduled task text" |
| 3 | `web_search` / `news_search` reported everything as "网络或超时" | typed transport errors (timeout / DNS / refused / unreachable / TLS / other) + HTTP 4xx≠5xx≠network, each with a next step | `NetworkFailure.kt`, `WebSearchTool.kt`, `NewsSearchTool.kt`, `AmapSearchTool.kt` |
| 4 | `browser_use` answered a bare "Invalid browser_use input" | field-level parse errors: template markers, missing/unknown action (with valid values), non-JSON | `BrowserActionInput.parseDetailed`, 2 Android call sites, iOS `parseDetailed` + 2 call sites |
| 5 | session `updated_at` newer than its last stored message | `minis-sessions-cli status` now reports `last_message_at` and `preview_only` | `SessionsOffloadHandler.kt` |
| 6 | skill docs taught bare `am start` (exit 127 in the sandbox) | both docs now wrap it: `android-shizuku-cli exec "am start …"` | device `haier-boiler/SKILL.md`, `memory/GLOBAL.md` |
| 7 | WeChat morning greeting failed on the first run (a11y unbound) | `ensure_a11y()` before opening the chat, and one retry after re-binding; rebind merges the service list instead of overwriting it | device `shared/wechat-greet/wechat_greet.py` |

## Findings that changed the shape of a fix

- **Fix 2's damage happens upstream.** A controlled probe (`punct-probe`)
  reproduced it: the tool call the model emitted already had ASCII punctuation
  and lost spans (`3 分钟` and `定时任务` vanished). That is the provider's
  tool-call template, not this app's argument parsing. The device-side repair
  is therefore best-effort plus a loud warning; the durable rule is "write the
  prompt verbatim and read it back", now in the runtime rules file.
- **Fix 5 is a design artifact, not data loss.** `updateSessionPreview` bumps
  `updated_at` and writes the preview without inserting a message row (that is
  how a long tool call shows live progress). The CLI previously could not tell
  that apart from "today's turns were never persisted". It can now.
- **Fix 7's root cause was ordering.** `am force-stop com.tencent.mm` can
  unbind Minis's own accessibility service; `ensure_a11y()` existed but only
  ran after a screenshot failed, which is too late — the open-chat taps had
  already been no-ops. It now runs before the chat is opened.
- **Fix 1 first shipped a lie of its own.** The waiter registered its latch
  and then released it with `if (finishedUtterances.remove(id) != null)`.
  `MutableSet.remove` returns `Boolean`, and `false != null` is true — so the
  latch was released instantly and `--wait` answered `completed` in ~8 ms
  while the phone was still speaking (caught by reading the engine callback
  log: `wait … via=latch elapsed=7` immediately followed by `evt start` and a
  real `evt done` 2.4 s later). Fixed to `if (finishedUtterances.remove(id))`.
  The gating rule from the same investigation stays: an `onDone` is only
  accepted after the matching `onStart`, and once started, the engine going
  quiet also counts as completion.

## Verification

- Source compiles (`assembleGithubPerf`), installed on the phone; iOS changes
  are source-only (no Xcode on this machine) and mirror the Android behaviour.
- `CjkPunctuationRepairTest` (JVM unit test) pins the repair/damage rules:
  full-width restoration only between CJK characters, no rewriting of
  paths/URLs, damage detected on empty segments. Green.
- On-device checks (2026-11-20, build 58):
  - `android-speak speak --wait` on 7 characters → `status: completed`,
    `elapsed_ms ≈ 1950`, and the engine callback log shows
    `register → evt start → evt done (1.9 s later)`. A `speak` without `--wait`
    keeps `speaking: true` for the duration and flips to false afterwards.
  - `minis-browser-use --json '{"action":"get_backbone<arg_key:1234>"}'` →
    error names the template marker; `{"action":"fly_to_mars"}` → unknown-action
    error listing all valid actions.
  - `minis-sessions-cli status --id 866a9435…` →
    `updated_at 2026-11-20 12:46`, `last_message_at 2026-11-18 11:09`,
    `preview_only: true` (the exact confusion the card described).
  - `minis-scheduled create --prompt "甲，乙，丙。"` stores U+FF0C/U+3002
    byte-exactly — the CLI path was never affected; only the model's
    `scheduled_task` tool channel mangles punctuation upstream.
  - Live `/var/minis/harness/rules.md` carries the new "Scheduled task text"
    section (appended, seed-once behaviour).
- Device-side (fixes 6/7): patched with timestamped backups,
  `python3 -m py_compile` clean, `a11y:rebound` present in the script.

## Not done here

- `scheduled_task` template damage itself is upstream; report it to the
  provider. If it persists, the next lever is a client-side "verbatim echo"
  check before the agent reports success (already in the rules file).
- `browser_use navigate` returning another tab's URL (card C06) has no clean
  repro; the field-level errors do not address it. Keep it on the card list.
- `android-speak` on iOS is already synchronous (`apple-speak` waits); its
  help text now says so explicitly, and the Android-only `--wait` asymmetry is
  documented in both helps.
