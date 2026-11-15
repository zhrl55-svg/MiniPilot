# Memory index prototype (Phase 1)

Read-only prototype for a more efficient memory retrieval layer. It parses the
existing Markdown memory files and compares two retrieval modes:

- **new**: BM25 (CJK bigrams + Latin words) + bigram Jaccard + entity tags +
  recency + importance, top-5.
- **baseline**: an entry-level approximation of the current `memory_get`
  (whitespace keywords, ALL must appear in a ±2-line window, file order, no
  ranking, no USER.md).

No app code is changed and no on-device model is used.

## Run

```bash
# point it at a local copy of the memory directory
python scripts/memory_index_prototype/memory_index.py \
  --memory-dir /path/to/memory eval \
  --queries scripts/memory_index_prototype/queries.json

# ad-hoc query
python scripts/memory_index_prototype/memory_index.py \
  --memory-dir /path/to/memory query "magpie 控制台 401" --top 5
```

Set `PYTHONIOENCODING=utf-8` on Windows if the console garbles Chinese.

## Parsing rules

- `GLOBAL.md` / `USER.md`: split on `## ` sections.
- `YYYY-MM-DD.md`: split on `<!-- YYYY-MM-DD HH:MM:SS -->` blocks; the first
  `## ` line in each block is the title.
- `SOUL.md`, dotfiles, `.bak.*`, and other non-memory files are ignored.
- The memory directory used for evaluation is a temporary local copy; never
  commit it.

## Metrics

- recall@5: expected title appears in the top 5.
- MRR: 1 / rank of the first expected title.
- top-5 chars vs the current fixed injection (GLOBAL + 3×200 log lines).

## Results (2026-11-14)

35 curated queries over a local copy of the current memory (47 entries:
18 GLOBAL, 8 USER, 21 daily):

| mode | recall@5 | MRR | top-5 chars |
|---|---|---|---|
| new index | 100% | 0.890 | ~6,500 |
| current `memory_get` approximation (no USER) | 62.9% | 0.571 | ~2,000 (fewer hits) |
| current scan + USER coverage | 71.4% | 0.657 | — |

- Current fixed injection (GLOBAL + 3×200 log lines): ~35.5k chars.
- New top-5 context: 18.3% of that, before per-entry truncation.
- Remaining top-1 misses are canonical-vs-mention (Compose → 维护), synonyms
  (歌源), and section-role cases; these are Phase 2 candidates, not a reason to
  use Pocket-0.3B for retrieval.

The query set is curated, not yet sampled from real sessions; treat these
numbers as directional until the eval is expanded to real user prompts.

## Notes

- The baseline excludes `USER.md` because the current `memory_get` does not
  search it. User-profile queries are therefore a coverage gain, not only a
  ranking gain.
- This is a lexical prototype. If recall@5 is insufficient on real queries,
  the next step is a small embedding model (not Pocket-0.3B), not a larger
  prompt.
