# roam-cli

CLI for Roam Research graphs. Read, write, search, and query from the terminal.

## Install

### Download binary (macOS arm64)

```bash
curl -L https://github.com/kkprop/roam-cli/releases/latest/download/roam-macos-arm64.tar.gz | tar xz
mv roam /usr/local/bin/
roam --version
```

### Build from source

```bash
git clone https://github.com/kkprop/roam-cli.git ~/roam-cli
cd ~/roam-cli
bb build
./dist/roam-cli --version
```

Requires [Babashka](https://github.com/babashka/babashka) >= 1.3.177.

## Setup

First run will prompt you to configure a graph:

```bash
bb setup
# Graph name (from your Roam URL): my-graph
# API token (from Roam Settings > API Tokens): roam-graph-token-...
# ✅ Connected to my-graph (42 pages)
```

Or create `~/.roam-cli/config.edn` manually:

```edn
{:roam-graphs
 {:personal {:token "roam-graph-token-..." :graph "my-graph"}
  :work     {:token "roam-graph-token-..." :graph "work-graph"}}
 :default-graph :personal}
```

```bash
bb test-connection personal
# ✅ Connected

bb graphs
# * personal → my-graph
#   work → work-graph
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

### Write

```bash
bb write <graph> "content"                    # Flat block on daily page
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
| `--titled "T"` | Title block + content as child (UID capture) |
| `--tree` | Parse markdown into nested block hierarchy |
| `--to <uid>` | Write under specific parent instead of daily page |

### Search

```bash
bb search <graph> <term>            # Search block content (fuzzy)
bb pages <graph> <term>             # Search page titles (fuzzy)
```

### Query

```bash
bb query <graph> '<datalog>'        # Raw Datalog query
```

### Utility

```bash
bb setup                            # Interactive config wizard
bb graphs                           # List configured graphs
bb test-connection <graph>          # Verify API token and graph access
bb build                            # Build standalone binary → dist/roam-cli
```

## Config

`~/.roam-cli/config.edn` (primary) or `~/roam-cli/config.edn` (legacy):

```edn
{:roam-graphs
 {:personal {:token "roam-graph-token-xxx" :graph "my-graph-name"}
  :work     {:token "roam-graph-token-yyy" :graph "company-graph"}}
 :default-graph :personal}
```

- `:graph` — graph name from your Roam URL (`roamresearch.com/#/app/<graph-name>`)
- `:token` — Graph API token from Roam Settings → API Tokens
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
├── cli/
│   └── commands.clj   # CLI dispatch
└── setup.clj          # Interactive config wizard
```

## UID Capture

Roam's `create-block` API doesn't return the new block's UID. For nested writes (`--titled`, `--tree`), roam-cli:

1. Creates the block
2. Waits 500ms for Roam to index
3. Queries by exact content + `create/time` desc
4. Uses the UID to nest children
