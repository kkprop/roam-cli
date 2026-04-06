# roam-cli Agent Guide

Commands for reading, writing, and searching Roam Research graphs from the terminal.

## Quick Start

```bash
bb setup                    # Interactive — prompts for graph name + API token
bb test-connection lisp     # Verify it works
bb graphs                   # List configured graphs
```

## Reading

```bash
# Read page by title
bb read lisp "October 14th, 2025"

# Read block by UID
bb read lisp KFl4IwYNL

# Deep pull — full nested tree with timestamps
bb pull lisp KFl4IwYNL

# Today's daily page
bb daily lisp

# Block in context — shows ancestor chain from root
bb context lisp DNiwQCo34
# ↳ Rate Limit Test
#   ↳ Level 2
#     ↳ Level 3
#       - target block content
```

`bb read` auto-detects: tries UID first, falls back to page title.

## Writing

Three modes: flat (default), titled, tree.

```bash
# Flat — single block on today's daily page
bb write lisp 'quick note from agent'

# Flat — under specific parent block
bb write lisp --to abc123uid 'child block'

# Titled — title block + content as child (UID capture)
bb write lisp --titled 'Meeting Notes' 'discussed rate limiting strategy'

# Tree — markdown parsed into nested block hierarchy
bb write lisp --tree '# Design Doc
## Architecture
three layers: protocol, core, cli
## Rate Limits
200ms between writes, 429 auto-retry'

# Tree — from file
bb write lisp --tree design.md

# Auto-detect — tree if markdown headers found, else flat
bb write lisp notes.md

# Update existing block
bb update lisp abc123uid 'corrected text'
```

Always verify writes landed:

```bash
bb write lisp 'test note'
bb search lisp 'test note'
# 🔗 xyz789 — test note
```

## Searching

```bash
# Search block content (fuzzy)
bb search lisp 'rate limit'
# 🔗 abc123 — Rate limit strategy for writes...
# 🔗 def456 — 429 rate limit auto-retry...

# Search page titles (fuzzy)
bb pages lisp 'dev'
# 📄 REPL-driven development
# 📄 dev-websocket

# Raw Datalog query
bb query lisp '[:find ?title :where [?p :node/title ?title]]'

# Find blocks created after timestamp
bb query lisp '[:find ?uid ?s :in $ ?ts :where [?b :block/uid ?uid] [?b :block/string ?s] [?b :create/time ?t] [(> ?t ?ts)]]'
```

## Rate Limits

- 200ms delay between write API calls (leaves in tree mode)
- 500ms delay after UID-capture writes (titled, tree parents)
- Auto-slows to 1000ms if >50 API calls in 60 seconds
- 429 responses: exponential backoff, up to 5 retries
- Bulk writes (>20 blocks): prints warning with time estimate

No action needed — pacing is automatic.

## Config

Location: `~/.roam-cli/config.edn` (primary) or `~/roam-cli/config.edn` (legacy).

```edn
{:roam-graphs
 {:lisp     {:token "roam-graph-token-xxx" :graph "lisp"}
  :personal {:token "roam-graph-token-yyy" :graph "my-graph"}
  :work     {:token "roam-graph-token-zzz" :graph "company"}}
 :default-graph :personal}
```

- `:graph` — name from Roam URL: `roamresearch.com/#/app/<graph-name>`
- `:token` — from Roam Settings → Graph → API Tokens
- Use the key (`:lisp`, `:personal`) as `<graph>` in commands
- `bb setup` to add graphs interactively

## Tips

- Use `:lisp` graph for testing — it's the designated test graph
- Always `bb search` after `bb write` to confirm delivery
- Use `--tree` for any markdown content with headers
- `bb pull` for deep inspection, `bb read` for quick look
- UIDs look like `KFl4IwYNL` — 9 alphanumeric chars
- Pipe content from files: `bb write lisp --tree report.md`
- Check connection first if writes fail: `bb test-connection lisp`
