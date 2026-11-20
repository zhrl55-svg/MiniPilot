# Self-improve loop — Phase 1: device-side evidence collection

Status: **landed and verified on Android** (2026-11-20, build 57).
iOS: not yet (the skill and output dir are platform-neutral; the scheduler must
exist on iOS before the daily trigger can be created).

## What it is

First step of the self-improvement loop (Harness² style): the device audits its
own day's conversations, extracts failure signals from real tool trajectories,
and writes structured **issue cards**. Nothing is modified automatically — the
cards are evidence for the PC-side improver (Phase 2), which will propose rule
changes and A/B them with `tools/probe/`.

```
daily 23:30 schedule
  └─ new chat "Scheduled · self-improve-daily"
       └─ reads /var/minis/skills/self-improve/SKILL.md
            └─ minis-sessions-cli list/messages over today's sessions
                 └─ issue cards -> /var/minis/shared/self-improve/*.json
```

## Files

| what | where |
|---|---|
| skill source (repo) | `tools/probe/self-improve-SKILL.md` |
| skill (device, global) | `/var/minis/skills/self-improve/SKILL.md` |
| issue cards | `/var/minis/shared/self-improve/` |
| schedule | `minis-scheduled` task `self-improve-daily` (id changes on recreate) |

Skill rules (summary): skip `source=debug` sessions; four failure signals
(loop, tool failure, user correction, empty promise); card schema
`{id, ts, symptom, evidence{session,message_index,tool,excerpt}, surface,
repro, severity}`; no secrets; no product-file writes.

## Output directory must be global, not per-session

`/var/minis/workspace` is **per-chat-session** (`SessionMounts.SESSION_SUBDIRS`
on Android). A scheduled task that writes there produces files that no other
session, and no external consumer, can ever see — the first real run lost 10
cards that way (they were still on disk under that session's private
workspace and had to be copied out explicitly).

Use `/var/minis/shared` (a `GLOBAL_SUBDIRS` entry on both platforms). The
schedule prompt names the shared path explicitly as a second line of defence,
because the prompt and the skill are edited independently.

## Schedule record (Android, 2026-11-20)

```sh
minis-scheduled create --label self-improve-daily --time 23:30 --repeat daily \
  --target new \
  --model 380b0b7c-0275-40c8-9294-681cbcc9b68d/workbuddy/deepseek-v4.1-flash \
  --prompt '读取并执行 self-improve skill（/var/minis/skills/self-improve/SKILL.md）：…只写 /var/minis/shared/self-improve/ 下的问题单（不要写 /var/minis/workspace/…）…'
```

`minis-scheduled` has no `update` subcommand — changing the prompt means
`delete --id` + `create`.

## Verification (2026-11-20)

1. `minis-scheduled run --id …` fired the real scheduling channel; the
   "Scheduled · self-improve-daily" session ran ~11 min / 86 messages and
   wrote **9 issue cards + 1 summary** (43.5 KB) via `minis-sessions-cli`
   over 4 non-debug sessions of the day.
2. The cards were written into the session-private workspace (bug above);
   moved to `/var/minis/shared/self-improve/` and confirmed byte-identical
   (sha256) by the same session.
3. A **fresh** debug session listed the shared dir and read
   `2026-11-20-summary.json` — cross-session visibility confirmed.
4. Schedule re-created pointing at the shared path; next fire 23:30.

Findings from the first run (already useful):

- `browser_use get_backbone` produced malformed arg JSON
  (`<arg_key:…>` tokens) → tool input validation failure.
- `web_search` / `news_search` failed on network, and the agent burned turns
  probing why; cards flag the retry pattern.
- Boiler-broadcast session used bare `am start` and a non-existent
  `android-device start-app` before `android-shizuku-cli exec` — skill line
  and GLOBAL.md both document only the bare command.
- Blind spot: sessions whose `updated_at` is today but whose messages stop
  earlier (device work done through other channels) — recorded as `info`,
  not silently dropped.

## Boundaries

- Reads conversations and `minis-sessions-cli` output; writes only under
  `/var/minis/shared/self-improve/`.
- Never edits `rules.md`, skills, source, settings; never reads secrets.
- `source=debug` sessions (probe traffic) are excluded from findings.

## Next phases

- **Phase 2 (PC side)**: a consumer that turns cards into candidate rule
  diffs (`/var/minis/harness/rules.md` overlays) and ranks by frequency ×
  severity. → Done, see `self-improve-phase2.md`: the pipeline is
  `pull_cards.py` → `improve.py` → `improve_propose.py` → `improve_ab.py`,
  and the first full cycle adopted **nothing** (every candidate came back
  `no-diff`: the rule layer is already saturated for these failure classes;
  the real work is code and documentation fixes).
- **Phase 3**: apply the code/doc fixes the cards point at, then re-run the
  cycle. Rule-file adoption only happens when a candidate earns an `adopt`.
