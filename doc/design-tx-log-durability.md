# Transaction log durability — sidecar SQLite backend

## Status

In-memory transaction log implemented (`nous.ctrl`, `nous.timeline`).
Durability deferred to this design. Not yet implemented.

---

## The gap

The in-memory log resets on `start!`. A session that produces three hours of
F# minor at 70 BPM accumulates recoverable transactional state — beat
positions, parameter values, source attribution — and loses it entirely on
process exit. The audio exists; the generative knowledge does not.

Durability closes that gap. The session log survives process restart, is
queryable after the fact, and is the foundation for session templates.

---

## Why the sidecar owns persistence

The JVM is not where you want reliable append-only IO under live session
conditions. GC pauses, process crashes, the realities of a live coding
environment make the JVM a poor choice for the write path. The sidecar is a
tight C++ process with predictable IO behaviour that already owns the
timing-critical output path.

The division of responsibility is clean:
- **JVM** — semantic model, transaction assembly, in-memory log, query API
- **Sidecar** — durable write path, SQLite session file

Transactions are not latency-sensitive. A `send-tx!` call after the in-memory
append is fire-and-forget. The sidecar buffers and writes; the JVM does not
wait for confirmation.

---

## Wire format — new IPC message type 0x30

The existing IPC framing is preserved:

```
[uint32 payload_len][uint8 0x30][uint8 reserved ×3][uint8[] payload]
```

`TxLog = 0x30` is added to the `MsgType` enum in `ipc.cpp`. The payload is a
custom binary layout — no external serialisation dependency, consistent with
the project's zero-external-dependency discipline.

### Payload layout (little-endian throughout)

```
[uint8[16]  tx_id]           -- squuid as raw 16 bytes (UUID hi + lo)
[float64    beat]            -- universal timeline beat position
[int64      wall_ns]         -- wall-clock nanoseconds (epoch)
[uint8      source_kind]     -- see enum below
[uint16     source_id_len]   -- byte length of source_id string
[uint8[]    source_id]       -- UTF-8 source identifier
[uint8      has_parent]      -- 1 if parent tx present, 0 otherwise
[uint8[16]  parent_id]       -- squuid bytes; only present if has_parent == 1
[uint16     n_changes]       -- number of path/value change records

-- repeated n_changes times:
[uint16     path_len]        -- byte length of EDN path string
[uint8[]    path]            -- EDN-serialised path e.g. "[:filter :cutoff]"
[uint16     before_len]      -- 0 if nil/absent (no prior value)
[uint8[]    before]          -- EDN value string; absent if before_len == 0
[uint16     after_len]
[uint8[]    after]           -- EDN value string
```

### `:source/kind` byte enum

| Value | Keyword           |
|-------|-------------------|
| 0     | `:user`           |
| 1     | `:loop`           |
| 2     | `:input`          |
| 3     | `:trajectory`     |
| 4     | `:watcher`        |
| 5     | `:supervisor`     |
| 6     | `:undo`           |
| 7     | `:error`          |

The sidecar stores the byte value; the JVM translates to/from keyword on
encode/decode. The sidecar never interprets source semantics — it stores and
retrieves opaquely.

Path and value strings are EDN. The sidecar writes them as SQLite TEXT columns
without parsing. The JVM reads them back via `clojure.edn/read-string` on the
query side. No C++ EDN parser required.

---

## SQLite schema

One session = one `.sqlite` file. The sidecar opens it at session start (on
receipt of a `SessionOpen = 0x31` message carrying the file path) and closes
it on `SessionClose = 0x32` or shutdown.

```sql
CREATE TABLE IF NOT EXISTS session (
  id          TEXT NOT NULL,   -- session identifier (ISO timestamp or uuid)
  started_at  INTEGER NOT NULL, -- wall_ns of first transaction
  bpm         REAL,            -- BPM at session start
  notes       TEXT             -- free-form session notes (set later)
);

CREATE TABLE IF NOT EXISTS transactions (
  id          BLOB PRIMARY KEY,  -- 16-byte squuid
  beat        REAL  NOT NULL,    -- universal timeline beat
  wall_ns     INTEGER NOT NULL,  -- wall-clock nanoseconds
  source_kind INTEGER NOT NULL,  -- byte enum
  source_id   TEXT,              -- UTF-8 source identifier
  parent_id   BLOB               -- 16-byte squuid or NULL
);

CREATE TABLE IF NOT EXISTS changes (
  tx_id       BLOB    NOT NULL REFERENCES transactions(id),
  seq         INTEGER NOT NULL,  -- change order within transaction (0-based)
  path        TEXT    NOT NULL,  -- EDN path string
  before      TEXT,              -- EDN value or NULL
  after       TEXT    NOT NULL   -- EDN value
);

CREATE INDEX IF NOT EXISTS idx_tx_beat     ON transactions(beat);
CREATE INDEX IF NOT EXISTS idx_change_path ON changes(path);
CREATE INDEX IF NOT EXISTS idx_change_tx   ON changes(tx_id);
```

`beat` indexed for range queries. `path` indexed for per-parameter history.
`tx_id` on changes for joining.

---

## SQLite in the sidecar — amalgamation

SQLite ships as a single-file C amalgamation (`sqlite3.h` + `sqlite3.c`).
This is the standard embedded deployment — no system dependency, no build
complexity, compiles anywhere the sidecar compiles. The amalgamation is
checked into the sidecar source tree under `cpp/nous-sidecar/vendor/`.

This is consistent with the project's zero-external-dependency discipline.
The SQLite amalgamation is ~250KB of well-audited C. It is not an external
dependency in the sense that matters — it has no transitive dependencies,
no package manager involvement, and no version drift risk.

---

## C++ implementation surface

Three additions to `ipc.cpp`:

1. **Schema setup** — `open_session_db(path)` opens the SQLite file, runs
   `CREATE TABLE IF NOT EXISTS` for the three tables, prepares the INSERT
   statements. Called on `SessionOpen` (0x31).

2. **Transaction handler** — `case MsgType::TxLog:` unpacks the binary
   payload, executes the prepared INSERT into `transactions` and one INSERT
   per change into `changes`, within a single SQLite transaction for
   atomicity.

3. **Session close** — `case MsgType::SessionClose:` flushes any pending
   writes and closes the SQLite connection cleanly.

The handler does not parse EDN. Path and value strings are stored as-is.
The sidecar is a reliable pipe between the JVM's semantic model and the
durable store.

---

## JVM implementation surface

Three additions to `nous.sidecar`:

```clojure
;; New message type constants
(def ^:private msg-type-tx-log        0x30)
(def ^:private msg-type-session-open  0x31)
(def ^:private msg-type-session-close 0x32)

(defn send-tx!
  "Send a transaction map to the sidecar for durable logging.
  Fire-and-forget — does not wait for confirmation."
  [tx] ...)

(defn open-session!
  "Open a durable session log at `path` (absolute path to .sqlite file).
  Called by nous.core/start! when durability is configured."
  [path] ...)

(defn close-session!
  "Flush and close the durable session log. Called by nous.core/stop!."
  [] ...)
```

`send-tx!` builds the binary payload from the transaction map, wraps it in
the standard IPC frame, and writes to the sidecar socket. The encoding mirrors
the existing `send-cc!` / `send-note-on!` pattern.

One addition to `nous.ctrl`:

```clojure
;; In set! and send-at!, after the in-memory append:
(when (sidecar/connected?)
  (sidecar/send-tx! tx))
```

The in-memory log and the durable log are always consistent — the in-memory
append happens inside the `swap!`; the durable write follows immediately after.
If the sidecar is not connected, only the in-memory log is written. No error;
durability degrades gracefully.

---

## Session file naming and location

Session files live in `(dirs/sessions-dir)` — the same user data directory
pattern used for device maps. Default: `~/.nous/sessions/`.

Filename: `<ISO-timestamp>-<session-id>.sqlite`
Example: `2026-04-20T21-34-00-berlin-study.sqlite`

The session ID is either auto-generated (timestamp) or supplied via
`(start! :session-id "berlin-study")`. The file path is passed to the sidecar
in the `SessionOpen` message.

---

## Query API (deferred — post-durability sprint)

Once the durable log exists, the query API becomes the interesting work:

```clojure
;; What was the filter cutoff at beat 3200?
(ctrl/tx-at [:filter :cutoff] 3200.0)

;; All transactions from a loop in a beat range
(ctrl/tx-range :beat [3000.0 3200.0] :source-id :bass-ostinato)

;; Full parameter history for a path
(ctrl/tx-history [:filter :cutoff])
```

These are SQL queries over the `changes` table filtered by path and joined to
`transactions` for beat range. The query API is a future sprint; the schema
is designed to support it from the start.

---

## Open questions

**Q77 — Session open timing**

Should `open-session!` be called automatically by `start!`, or explicitly by
the user? Automatic is more convenient; explicit gives control over whether a
session is recorded at all. Recommendation: automatic when a sessions
directory exists; opt-out via `(start! :no-log true)`.

**Q78 — Log compaction**

A long session accumulates many transactions. High-frequency parameter sweeps
(LFO at 100Hz for an hour) produce ~360,000 entries for a single path. Options:
- Store every transaction (complete fidelity, large files)
- Compaction: for rapid successive writes to the same path, keep only
  beat-boundary samples. Lossy but manageable.
- Configurable sample rate per path (`:log/rate-hz` in node-meta)

Compaction belongs in the JVM before `send-tx!`, not in the sidecar.

**Q79 — Schema transactions in the log**

`defdevice-model` and `defrealization` should themselves be log entries —
schema changes as first-class transactions. This requires either a separate
schema transaction type (new message 0x33) or encoding schema changes as
regular transactions at a well-known path (e.g. `[:nous/schema ...]`).
The latter is simpler and consistent with Datomic's approach.
