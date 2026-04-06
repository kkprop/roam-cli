# roam-cli

CLI for Roam Research graphs. Read, write, search, and query from the terminal.

## Requirements

- [Babashka](https://github.com/babashka/babashka) >= 1.3.177
- Roam Research API token ([generate here](https://roamresearch.com/#/app/developer))

## Setup

```bash
git clone <repo> ~/roam-cli && cd ~/roam-cli
```

Create `config.edn`:

```edn
{:roam-graphs
 {:personal {:token "roam-graph-token-..." :graph "my-graph"}
  :work     {:token "roam-graph-token-..." :graph "work-graph"}}
 :default-graph :personal}
```

Test it:

```bash
bb test-connection personal
# ✅ Connected
```

## Commands

### Read

```bash
bb read <graph> <page-or-uid>       # Auto-detect page title or block UID
bb pull <graph> <uid>               # Deep pull block with all nested children
bb daily <graph>                    # Show today's daily page
bb context <graph> <uid>            # Show block with full ancestor chain
```

Examples:

```bash
bb read lisp "REPL-driven development"    # Read a page
bb read lisp D18PudtCE                    # Read a block by UID
bb pull lisp D18PudtCE                    # Deep pull with timestamps
bb daily lisp                             # Today's daily note
bb context lisp DNiwQCo34                 # Ancestor path → target block
```

Context output shows the path from root to block:

```
↳ Rate Limit Test
  ↳ Level 2
    ↳ Level 3
      ↳ Level 4
        - Level 4 content.
```

### Write

```bash
bb write <graph> "content"                    # Auto-detect mode, write to daily
bb write <graph> --to <uid> "content"         # Write under specific parent
bb write <graph> --titled "Title" "content"   # Title block + content as child
bb write <graph> --tree file.md               # Parse markdown → nested blocks
bb write <graph> file.md                      # Auto-detect: tree if has headers
bb update <graph> <uid> "new content"         # Update existing block
```

Write modes:

| Flag | Behavior |
|---|---|
| _(default)_ | Auto-detect: `--tree` if markdown headers found, else flat |
| `--flat` | Single block |
| `--titled "T"` | Title block + content as child (UID capture) |
| `--tree` | Parse markdown into nested block hierarchy |
| `--to <uid>` | Write under specific parent instead of daily page |

Examples:

```bash
bb write lisp "quick note"                          # Flat block on daily page
bb write lisp --titled "Meeting Notes" notes.md     # Title + file as child
bb write lisp --tree design.md                      # Markdown → block tree
bb write lisp --to abc123uid "child block"          # Under specific parent
bb update lisp abc123uid "corrected text"           # Edit existing block
```

### Search

```bash
bb search <graph> <term>            # Search block content (fuzzy)
bb pages <graph> <term>             # Search page titles (fuzzy)
```

Examples:

```bash
bb search lisp "REPL"
# 🔗 abc123 — REPL-driven development is the key...
# 🔗 def456 — Open a REPL and try this...

bb pages lisp "dev"
# 📄 REPL-driven development
# 📄 dev-websocket
```

### Query

```bash
bb query <graph> '<datalog>'        # Raw Datalog query, tabular output
```

Examples:

```bash
bb query lisp '[:find ?title :where [?p :node/title ?title]]'

bb query lisp '[:find ?uid ?s :in $ ?t :where [?b :block/uid ?uid] [?b :block/string ?s] [(clojure.string/includes? ?s ?t)]]' 
```

### Utility

```bash
bb test-connection <graph>          # Verify API token and graph access
```

## Config

`~/roam-cli/config.edn` — map of named graphs with API tokens:

```edn
{:roam-graphs
 {:personal {:token "roam-graph-token-xxx" :graph "my-graph-name"}
  :work     {:token "roam-graph-token-yyy" :graph "company-graph"}}
 :default-graph :personal}
```

- `:graph` is the graph name from your Roam URL (`roamresearch.com/#/app/<graph-name>`)
- `:token` is a Graph API token from Roam's developer settings
- Use the key name (`:personal`, `:work`) as `<graph>` in all commands

## Architecture

Three layers — protocol is swappable for Obsidian/Logseq:

```
src/roam/
├── protocol/          # Transport layer (swappable)
│   ├── protocol.clj   # Selectors, EID helpers, constants
│   └── roam.clj       # Roam HTTP API, auth, 429 retry
├── core/              # API-agnostic operations
│   ├── read.clj       # pull, daily, context, query
│   ├── write.clj      # create, update, move, write modes
│   ├── search.clj     # fuzzy search, exact match, temporal
│   └── hierarchy.clj  # Markdown → block tree parser
└── cli/
    └── commands.clj   # Future: unified CLI dispatch
```

| Layer | Responsibility | Swappable? |
|---|---|---|
| Protocol | HTTP transport, auth, 429 backoff, rate limiting | Yes — replace `roam.clj` for Obsidian/Logseq |
| Core | Read/write/search ops, markdown parsing, UID capture | Stable interface |
| CLI | Command parsing, output formatting | Surface |

Core never touches HTTP or auth. Protocol handles all retry logic (exponential backoff on 429, up to 5 attempts).

## UID Capture

Roam's `create-block` API doesn't return the new block's UID. For nested writes (`--titled`, `--tree`), roam-cli:

1. Creates the block
2. Waits 500ms for Roam to index
3. Queries by exact content + `create/time` desc
4. Uses the UID to nest children

This is the same pattern used by the reference implementation in `qq/roam/client.clj`.
