# Self-improve loop — Phase 2: cards → clusters → proposals → A/B

Status: **pipeline works end-to-end on the real device** (2026-11-20).
Phase 1 (device-side evidence collection) is in `self-improve-loop.md`.

## The pipeline

```
device 23:30 ──► /var/minis/shared/self-improve/*.json      (Phase 1, device)
                     │
                     ▼  pull_cards.py      (device → PC, temp dir)
                 cards/<date>/*.json
                     │
                     ▼  improve.py         (deterministic: validate → cluster → rank)
                 clusters.json + dossier.md + worksheets/C*.md
                     │
                     ▼  improve_propose.py (headless claude: writes candidate rule + probe)
                 candidates/C*.json
                     │
                     ▼  improve_ab.py      (real device: baseline vs system-prompt overlay)
                 ab/C*/ab.json + ab.md  →  adopt | no-diff | regress
```

Three scripts, one verdict each. Nothing reaches the device's rules.md
without an `adopt` verdict.

## The scripts

| tool | what it does | where output goes |
|---|---|---|
| `tools/probe/pull_cards.py` | tars the device's card dir through `debug.shellExecute` and unpacks it on the PC | **outside the repo** (temp dir) |
| `tools/probe/improve.py` | validates the card schema, clusters by IDF-weighted symptom overlap, ranks by severity, writes a dossier + one worksheet per cluster | outside the repo |
| `tools/probe/improve_propose.py` | headless `claude -p` per cluster → candidate rule text + a probe task | outside the repo (`--export` for reviewed text only) |
| `tools/probe/improve_ab.py` | runs the probe on the phone twice (plain vs `systemPromptAppend`), then a randomized-order judge decides adopt / no-diff / regress | outside the repo |

Cards and dossiers quote real conversations, so **none of them may live in the
repo**; `pull_cards.py` and `improve.py` refuse an in-repo output path
unless `--allow-in-repo` is passed.

## Transport notes (learned the hard way)

- `debug.readFile` **kills the debug server's accept loop** — the socket stops
  answering and the app must be restarted. Use `debug.shellExecute` (plain
  guest shell) instead; it reads the global `/var/minis/shared` tree fine.
- `debug.shellExecute` returns `output`, `exit_code`, `duration_ms`. A
  `tar -czf - *.json | base64 | tr -d "\n"` bundle is the transport of choice
  for binary-ish payloads.
- `run_device.py` nests a timestamped directory under `--runs-dir`, so the
  transcript is at `<runs-dir>/<timestamp>-device/<label>__baseline.messages.json`,
  not directly in `--runs-dir`. `improve_ab.py` globs for it.

## Two methodology bugs we had to fix

1. **Over-merging.** Clustering first by `(surface, tool)` put three
   unrelated skill cards in one bucket (all of them had used `shell_execute`),
   producing a "proposal" that needed three unrelated fixes. Now every card
   starts as its own group and groups merge only on IDF-weighted token
   overlap (a token that appears in a single card is strong evidence; a token
   every card uses carries none).
2. **A biased judge.** The judge was handed "answer A = baseline, answer B =
   overlay" while its prompt claimed the order was random. It produced a
   false `adopt` on a candidate whose two answers were both compliant. Now
   the order is genuinely randomised and the verdict is mapped back to
   (baseline, overlay); the criteria also require *exactly one* side to
   follow the rule for `adopt`.

## Probe strength decides whether a verdict means anything

A knowledge-style probe ("web_search 报『网络或超时』时第一步该做什么？")
cannot detect a rule change: the model answers from prior knowledge whether
or not the rule is in the prompt. The proposer is now told to prefer
**execute-style** probes — the agent really does something and reports an
observable fact.

Even so, on 2026-11-20 every candidate still came back `no-diff`; the
baseline already verified files thoroughly (existence, size, first line, md5,
mtime). That is a finding, not a tooling failure: see below.

## Result on the 2026-11-20 cards

9 cards → 9 clusters (no two shared a root cause) → 3 rule candidates →
3 A/B runs, all `no-diff`.

| cluster | lever | verdict | A/B |
|---|---|---|---|
| C01 web_search/news_search 报错语义 | rule | rule（2 条） | **no-diff** — baseline already splits credential vs network via curl + status code |
| C02 无障碍服务被解绑导致首轮失败 | skill | code | 落点是技能脚本的前置检查，不是行为规则 |
| C03 会话 updated_at 更新但消息没落库 | guardrail | code | 落库缺陷，行为规则管不了 |
| C04 技能文档写裸 `am start`，沙箱没这命令 | skill | code | 文档写错，规则兜底还会和文档冲突 |
| C06 navigate 回执与实际页面不一致 | tool_desc | code + 1 条规则 | **no-diff** — baseline 已要求用 execute_js 核对 |
| C07 写完文件未验证存在 | skill | rule（1 条） | **no-diff** — baseline 已逐项回读验证 |
| C08 android-speak 的 `status:speaking` 被当成已播报 | tool_desc | — | 缺完成回执，属工具能力缺陷 |
| C09 定时任务 prompt 里的全角逗号被改写 | tool_desc | — | 参数处理 bug |

**Conclusion: the rule layer is saturated for these failure classes.**
SOUL.md ("工具的自报不算证据") plus the existing runtime rules already make
the model verify, split causes and re-read files. The A/B gate did its job
by *refusing* to add redundant rules — which would have cost prompt tokens
and risked conflicting with rules that already work.

What the cards actually demand is code and documentation work, not prompt
work:

1. `android-speak` needs a completion signal (`--wait` / a spoken-at time).
2. `scheduled_task` must not rewrite full-width Chinese punctuation.
3. `web_search` / `news_search` should distinguish credential errors from
   unreachable sources instead of one "网络或超时" string.
4. `browser_use` should reject malformed action values with a field-level
   error, and `navigate` should report the tab it actually acted on.
5. The session store must persist the messages it reports in `updated_at`.
6. `haier-boiler/SKILL.md` and `GLOBAL.md` must say
   `android-shizuku-cli exec "am start …"`, not bare `am start`.
7. The WeChat morning-greeting script must check the accessibility service
   before the first `open-chat` and rebind via Shizuku if needed.

Items 1–7 are product fixes, not harness fixes; they are tracked as a batch
(the user's standing rule: issues found during use get batched, not fixed one
by one).

## Cost

One daily cycle: ~11 min of device model time for the self-diagnosis, plus
~30–60 s per candidate for the propose step and ~60–100 s per candidate for
the two device runs plus the judge.

## Known limits

- The proposer runs headless `claude -p`; with the local proxy model in use
  it sometimes returns a schema-shaped non-answer or an English rule
  (`unrecognized_model` shows up occasionally). The script retries once and
  saves the raw text; a stronger model behind the proxy would make the rule
  text noticeably better.
- Clustering is symptom-text based. A card whose symptom is written in
  unrelated words will not merge with its sibling even when the root cause
  is the same.
- The judge is an LLM reading two answers. It is biased towards "same" when
  both answers are long and correct; verdicts are read together with the two
  answers, which are stored next to the verdict in `ab/C*/ab.json`.
