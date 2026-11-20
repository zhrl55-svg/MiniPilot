# Runtime harness rules (`/var/minis/harness/rules.md`)

Status: **Android landed (build-tested); iOS source-landed (not compiled).**
Since 2026-11-20 (commit pending).

## What it is

The agent's behavioural rules used to be compiled into the base system prompt
(`ChatViewModel.kt` on Android, `AIChatViewModel+SystemPrompt.swift` on iOS).
They now live in a Markdown file read at every system-prompt build:

| | path |
|---|---|
| agent / shell sees | `/var/minis/harness/rules.md` |
| Android host | `<filesDir>/minis-global/harness/rules.md` |
| iOS host | `<AppGroup>/MinisFileProvider/harness/rules.md` |

Precedence: **file content wins**; missing or blank → compiled default
(`HarnessRules.DEFAULT_RULES` / `HarnessRules.defaultText`). The default is
written to the file once on first launch (`ensureSeeded`) and never overwrites
edits. The two platforms share the same runtime path, so one file is the single
source of truth — no more Android/iOS prompt drift for these rules.

**Mount table (required on both platforms).** `/var/minis/harness` is a
bind/symlink into the app container, not a real guest directory. Android has
TWO lists and both must include `harness`:

- `PRootKernel.registerGlobalBindMounts` — the one-shot `ShellExecutor` path
  (`buildProotCommand`). Also creates the guest placeholder dir; PRoot binds
  onto an existing path, and a brand-new global dir does not exist in the
  shipped rootfs image.
- `SessionMounts.GLOBAL_SUBDIRS` — the **persistent agent shell**. Missing
  here is the nastier failure: PRoot only prints one WARNING for a missing
  bind source and silently drops the binding, so the guest sees the empty
  rootfs placeholder and the app "successfully" writes files nobody reads.
- iOS: `ensureMinisSymlinks` must map `minisHarnessPersistentDir` →
  `/var/minis/harness`.

Symptom when an entry is missing: `ensureSeeded` logs `exists=true` on the
host, yet the agent's shell reports `/var/minis/harness` empty or absent.

Placement in the prompt: appended at the very end, after the memory fragments
and device context, before the debug-probe overlay. The cache-stable prefix is
untouched.

## Migrated groups

| group | status | note |
|---|---|---|
| Execution discipline | ✅ migrated 2026-11-20 | platform-neutral wording; scheduling mechanics stay in each platform's "Scheduled tasks" section |
| Tone and style | ✅ migrated 2026-11-20 | text was byte-identical on both platforms |

## Audit result for the remaining groups

The two platform prompts have **drifted**, so most groups cannot be moved
without first deciding which version is correct:

| group | blocker |
|---|---|
| Shared directory list | GLOBAL.md line differs: Android "writable, you may update it", iOS "read-only, user-maintained"; mounts line names different entry points |
| minis:// URL rules | iOS has auto-play + tappable-preview bullets; Android has an inline media-type list and a browser-gated note |
| File creation guidelines | **contradictory**: Android says heredocs "may mis-parse … avoid whenever possible"; iOS says heredocs "work reliably". Also Android-only ICMP/Java/rg bullets |
| Environment variables | Android points at `minis-self links`; iOS lists paths inline |
| Self-reference (`minis-self`) | Android-only command set |
| Scheduled tasks | genuinely platform-specific (alarm vs best-effort/Shortcuts) |
| Tool bullets / schemas / switches | must match the compiled schema; guardrails — never self-editable |

Next migration requires a **two-platform text decision** first (pick one
authentic version per group), then the mechanical move. Do not blindly copy
one side into the shared file.

## How to migrate another rule group

1. Cut the static text out of the platform prompt builders (Android +
   iOS) — leave a `[T-harness-rules-file]` pointer comment.
2. Append the same text to `DEFAULT_RULES` (Android `HarnessRules.kt`) and
   `defaultText` (iOS, at the end of `AIChatViewModel+SystemPrompt.swift`).
   Keep the two byte-identical.
3. Existing installs already have a seeded file: the new default only applies
   to fresh installs. For your own device, append the new text to
   `/var/minis/harness/rules.md` manually (or reinstall after clearing data).
4. Verify on device with the probe corpus (`tools/probe/run_device.py`);
   the rule's behaviour probes should stay green.

## Why this matters

- Prompt changes no longer need a 3–5 minute build + install.
- A/B becomes "swap the file" (`--system-overlay` for experiments, the file
  for durable adoption).
- Versioning/rollback is a file copy.
- A future self-improvement loop has a safe, reversible place to write.

## Not migrated (still compiled)

Tool descriptions/schemas, tool switches, approval rules, memory/skill
injection, identity (`SOUL.md` already file-backed), runtime context. Those
change rarely and some are guardrails — they must not be self-editable.
