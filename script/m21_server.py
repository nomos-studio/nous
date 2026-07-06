#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0
#
# nous Music21 persistent server — JSON-lines stdio protocol
#
# Replaces the one-shot m21_extract.py with a long-running process that
# eliminates the 2-4 second music21 startup cost on every call.
#
# Lifecycle:
#   Started lazily by nous.m21/ensure-server! on first corpus request.
#   Exits cleanly when stdin closes (parent JVM process died — free crash
#   detection via OS pipe semantics, no heartbeat needed).
#   Can also be terminated cleanly via {"op": "shutdown"}.
#
# Protocol:
#   Requests:  one JSON object per line on stdin
#   Responses: one JSON object per line on stdout
#
# Bach-specific ops (backward-compatible):
#   {"op":"load","bwv":"371","mode":"chords"} → {"status":"ok","session":"...","edn":"[...]"}
#   {"op":"load","bwv":"371","mode":"parts"}  → {"status":"ok","session":"...","edn":"{...}"}
#   {"op":"list"}                             → {"status":"ok","data":[248,253,...]}
#
# General corpus ops:
#   {"op":"search","composer":"palestrina"}   → {"status":"ok","data":[{"path":"...","title":"...","composer":"..."},...]}
#   {"op":"search","fileExtension":"abc"}     → same shape
#   {"op":"load-work","path":"palestrina/kyrie.mxl","mode":"parts"}
#                                             → {"status":"ok","session":"...","edn":"{...}"}
#   {"op":"load-work","path":"...","mode":"chords"}    → vector of chord maps
#   {"op":"load-work","path":"...","mode":"intervals"} → per-voice {from to semitones dur/beats}
#
# Utility ops:
#   {"op":"ping"}                             → {"status":"ok"}
#   {"op":"shutdown"}                         → {"status":"ok"}  then exit(0)
#   {"op":"parse-midi","path":"/abs/path"}    → {"status":"ok","notes":"[...]","tempo":120.0,...}
#   Any error:                                → {"status":"error","message":"..."}
#
# Session cache:
#   First load of a path+mode extracts via music21 (~100-500ms per work).
#   Subsequent requests for the same session return instantly from the in-memory
#   _sessions dict. The Clojure side maintains its own two-level (mem+disk) cache
#   on top of this, so Python sessions are only populated once per server lifetime.

import sys
import os
import argparse
import json
import io
import socket as _socket

# Suppress music21's noisy startup banner
os.environ["MUSIC21_SUPPRESS_STARTUP"] = "1"

try:
    from music21 import corpus, note, chord
except ImportError:
    print(json.dumps({"status": "error", "message": "music21 not installed"}), flush=True)
    sys.exit(1)

# ---------------------------------------------------------------------------
# Session cache: {session_key: edn_string}
# ---------------------------------------------------------------------------

_sessions = {}

# ---------------------------------------------------------------------------
# Voice name → EDN keyword mapping (for parts mode)
# ---------------------------------------------------------------------------

_VOICE_BY_NAME = {
    "soprano": ":soprano", "s.": ":soprano",
    "alto":    ":alto",    "a.": ":alto",
    "tenor":   ":tenor",   "t.": ":tenor",
    "bass":    ":bass",    "b.": ":bass",
}
_VOICE_BY_INDEX = [":soprano", ":alto", ":tenor", ":bass"]


def _voice_key(part, index):
    name = (part.partName or "").strip().lower()
    return _VOICE_BY_NAME.get(name, _VOICE_BY_INDEX[min(index, 3)])


def _unique_voice_keys(parts):
    """Return a list of unique EDN keyword strings for each part.

    When two parts share the same base key (e.g. two tenors both map to
    :tenor), the second gets :tenor-2, the third :tenor-3, and so on.
    This prevents duplicate-key errors in the EDN map output.
    """
    seen = {}
    keys = []
    for i, part in enumerate(parts):
        base = _voice_key(part, i)
        if base not in seen:
            seen[base] = 1
            keys.append(base)
        else:
            seen[base] += 1
            keys.append(f"{base}-{seen[base]}")
    return keys


# ---------------------------------------------------------------------------
# EDN rendering
# ---------------------------------------------------------------------------

def _edn_val(v):
    """Render a Python value as an EDN value string."""
    if isinstance(v, bool):
        return "true" if v else "false"
    elif isinstance(v, (int, float)):
        return str(v)
    elif isinstance(v, list):
        return "[" + " ".join(_edn_val(x) for x in v) + "]"
    elif isinstance(v, str):
        # Already-rendered EDN (keyword, vector, map, tagged literal, quoted string)
        if v and v[0] in (":", "[", "{", "#", '"'):
            return v
        # Plain Python string — escape and quote as EDN string literal
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return str(v)


def _edn_map(pairs):
    """Render a list of (key, value) pairs as an EDN map string."""
    return "{" + " ".join(f"{k} {_edn_val(v)}" for k, v in pairs) + "}"


# ---------------------------------------------------------------------------
# Bach corpus helpers
# ---------------------------------------------------------------------------

def _find_score(bwv_id):
    """Return parsed music21 Score for bwv_id, or raise RuntimeError."""
    try:
        return corpus.parse(f"bach/bwv{bwv_id}")
    except Exception:
        pass

    import pathlib, re
    try:
        bach_dir = pathlib.Path(corpus.getComposer("bach")[0]).parent
    except Exception:
        raise RuntimeError(f"bwv{bwv_id} not found in corpus")

    candidates = sorted(bach_dir.glob(f"bwv{bwv_id}.*.mxl"))
    if not candidates:
        candidates = sorted(bach_dir.glob(f"bwv{bwv_id}*.mxl"))
    if not candidates:
        raise RuntimeError(f"bwv{bwv_id} not found in corpus")

    try:
        return corpus.parse(str(candidates[-1]))
    except Exception as e:
        raise RuntimeError(f"bwv{bwv_id}: parse failed — {e}")


def _extract_chords_from_score(score, label):
    """Return EDN string for a chordified score (Phase 1 / chords mode)."""
    chordified = score.chordify()
    buf = io.StringIO()
    buf.write(f"; {label}\n[\n")
    for el in chordified.flatten().notesAndRests:
        dur = float(el.duration.quarterLength)
        if dur <= 0:
            continue
        if isinstance(el, note.Rest):
            buf.write(f" {_edn_map([(chr(58)+'rest', True), (chr(58)+'dur/beats', round(dur, 6))])}\n")
        elif isinstance(el, chord.Chord):
            midis = sorted(p.midi for p in el.pitches)
            buf.write(f" {_edn_map([(chr(58)+'pitches', midis), (chr(58)+'dur/beats', round(dur, 6))])}\n")
    buf.write("]\n")
    return buf.getvalue()


def _extract_chords(bwv_id):
    """Return EDN string for the chordified Bach chorale (backward-compat wrapper)."""
    return _extract_chords_from_score(_find_score(bwv_id), f"BWV {bwv_id}")


def _extract_parts_from_score(score, label):
    """Return EDN string for per-voice event sequences (Phase 2 / parts mode)."""
    parts = list(score.parts)
    keys = _unique_voice_keys(parts)
    buf = io.StringIO()
    buf.write(f"; {label} parts\n{{\n")
    for i, part in enumerate(parts):
        key = keys[i]
        buf.write(f" {key}\n [\n")
        for el in part.flatten().notesAndRests:
            dur = float(el.duration.quarterLength)
            if dur <= 0:
                continue
            if isinstance(el, note.Rest):
                buf.write(f"  {_edn_map([(chr(58)+'rest', True), (chr(58)+'dur/beats', round(dur, 6))])}\n")
            elif isinstance(el, note.Note):
                buf.write(f"  {_edn_map([(chr(58)+'pitch', el.pitch.midi), (chr(58)+'dur/beats', round(dur, 6))])}\n")
            elif isinstance(el, chord.Chord):
                midi = sorted(p.midi for p in el.pitches)[-1]
                buf.write(f"  {_edn_map([(chr(58)+'pitch', midi), (chr(58)+'dur/beats', round(dur, 6))])}\n")
        buf.write(" ]\n")
    buf.write("}\n")
    return buf.getvalue()


def _extract_parts(bwv_id):
    """Return EDN string for per-voice SATB (backward-compat wrapper)."""
    return _extract_parts_from_score(_find_score(bwv_id), f"BWV {bwv_id}")


def _list_chorales():
    """Return sorted list of available BWV integers."""
    import pathlib, re
    try:
        bach_dir = pathlib.Path(corpus.getComposer("bach")[0]).parent
    except Exception:
        return []
    nums = set()
    for f in bach_dir.glob("bwv*.mxl"):
        m = re.match(r"bwv(\d+)", f.stem)
        if m:
            nums.add(int(m.group(1)))
    return sorted(nums)


# ---------------------------------------------------------------------------
# General corpus helpers
# ---------------------------------------------------------------------------

def _find_work(path):
    """Return parsed music21 Score for any corpus path, or raise RuntimeError."""
    try:
        return corpus.parse(path)
    except Exception as e:
        raise RuntimeError(f"{path!r} not found or parse failed: {e}")


def _extract_intervals_from_score(score, label):
    """Return EDN string for per-voice interval sequences.

    Each event: {:from midi :to midi :semitones N :dur/beats Q}
    Rests are skipped; only note→note transitions are recorded.
    """
    parts = list(score.parts)
    keys = _unique_voice_keys(parts)
    buf = io.StringIO()
    buf.write(f"; {label} intervals\n{{\n")
    for i, part in enumerate(parts):
        key = keys[i]
        buf.write(f" {key}\n [\n")
        pitched = [el for el in part.flatten().notesAndRests
                   if isinstance(el, (note.Note, chord.Chord))]
        for j in range(len(pitched) - 1):
            a, b = pitched[j], pitched[j + 1]
            a_midi = (sorted(p.midi for p in a.pitches)[-1]
                      if isinstance(a, chord.Chord) else a.pitch.midi)
            b_midi = (sorted(p.midi for p in b.pitches)[-1]
                      if isinstance(b, chord.Chord) else b.pitch.midi)
            semitones = b_midi - a_midi
            dur = float(a.duration.quarterLength)
            buf.write(
                f"  {_edn_map([(':from', a_midi), (':to', b_midi), (':semitones', semitones), (':dur/beats', round(dur, 6))])}\n"
            )
        buf.write(" ]\n")
    buf.write("}\n")
    return buf.getvalue()


def _search_corpus(composer=None, file_ext=None, title=None, limit=100):
    """Search the corpus and return a list of {'path','title','composer'} dicts."""
    kwargs = {}
    if composer:
        kwargs["composer"] = composer
    if file_ext:
        kwargs["fileExtensions"] = [file_ext]
    if title:
        kwargs["title"] = title
    try:
        results = corpus.search(**kwargs)
    except Exception as e:
        raise RuntimeError(f"corpus.search failed: {e}")
    out = []
    for r in list(results)[:limit]:
        try:
            md = r.metadata
            title_str    = str(md.title    or "") if md else ""
            composer_str = str(md.composer or "") if md else ""
        except Exception:
            title_str = composer_str = ""
        out.append({
            "path":     str(r.sourcePath),
            "title":    title_str,
            "composer": composer_str,
        })
    return out


# ---------------------------------------------------------------------------
# Op handlers
# ---------------------------------------------------------------------------

def _handle_load(req):
    bwv_id = req.get("bwv")
    mode = req.get("mode", "chords")
    session_key = f"bwv{bwv_id}-{mode}"
    if session_key in _sessions:
        return {"status": "ok", "session": session_key, "edn": _sessions[session_key]}
    try:
        if mode == "parts":
            edn = _extract_parts(bwv_id)
        else:
            edn = _extract_chords(bwv_id)
    except Exception as e:
        return {"status": "error", "message": str(e)}
    _sessions[session_key] = edn
    return {"status": "ok", "session": session_key, "edn": edn}


def _handle_list(_req):
    try:
        return {"status": "ok", "data": _list_chorales()}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def _handle_search(req):
    try:
        results = _search_corpus(
            composer=req.get("composer"),
            file_ext=req.get("fileExtension"),
            title=req.get("title"),
            limit=int(req.get("limit", 100)),
        )
        return {"status": "ok", "data": results}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def _handle_load_work(req):
    path = req.get("path")
    if not path:
        return {"status": "error", "message": "path required"}
    mode = req.get("mode", "parts")
    session_key = f"work:{path}:{mode}"
    if session_key in _sessions:
        return {"status": "ok", "session": session_key, "edn": _sessions[session_key]}
    try:
        score = _find_work(path)
        if mode == "parts":
            edn = _extract_parts_from_score(score, path)
        elif mode == "chords":
            edn = _extract_chords_from_score(score, path)
        elif mode == "intervals":
            edn = _extract_intervals_from_score(score, path)
        else:
            return {"status": "error", "message": f"unknown mode: {mode!r}"}
    except Exception as e:
        return {"status": "error", "message": str(e)}
    _sessions[session_key] = edn
    return {"status": "ok", "session": session_key, "edn": edn}


def _handle_ping(_req):
    return {"status": "ok"}


def _handle_shutdown(_req):
    return {"status": "ok", "__shutdown": True}


# ---------------------------------------------------------------------------
# MIDI file parsing — for midi-repair pipeline
# ---------------------------------------------------------------------------

def _midi_note_to_edn(n, offset_beats, tempo_bpm):
    """Render a music21 Note as an EDN step map string."""
    midi = n.pitch.midi
    dur  = float(n.quarterLength)
    vel  = n.volume.velocity if n.volume.velocity else 64
    chan = (n.activeSite.id if hasattr(n.activeSite, 'id') else 0) or 0
    return _edn_map([
        (":pitch/midi",  midi),
        (":start/beats", round(float(offset_beats), 6)),
        (":dur/beats",   round(dur, 6)),
        (":velocity",    vel),
        (":channel",     chan),
    ])


def _handle_parse_midi(req):
    """Parse a MIDI file and return all note events as EDN.

    Request:  {"op":"parse-midi","path":"/abs/path/to/file.mid"}
    Response: {"status":"ok","notes":"[{:pitch/midi 60 ...} ...]",
               "tempo":120.0,"time-sig":"4/4","bars":32}
    """
    path = req.get("path", "")
    if not path:
        return {"status": "error", "message": "parse-midi requires 'path'"}
    try:
        from music21 import converter as m21conv, tempo as m21tempo, meter
        import os
        if not os.path.isfile(path):
            return {"status": "error", "message": f"file not found: {path}"}

        score = m21conv.parse(path)
        flat  = score.flatten()

        # Extract tempo
        tempos = flat.getElementsByClass(m21tempo.MetronomeMark)
        bpm    = float(tempos[0].number) if tempos else 120.0

        # Extract time signature
        tsigs  = flat.getElementsByClass(meter.TimeSignature)
        tsig   = tsigs[0].ratioString if tsigs else "4/4"

        # Extract all notes (not rests) with beat offsets
        notes_edn = []
        for n in flat.notes:
            if hasattr(n, 'pitch'):                   # Note
                notes_edn.append(_midi_note_to_edn(n, n.offset, bpm))
            elif hasattr(n, 'pitches') and n.pitches: # Chord
                for p in n.pitches:
                    import music21.note as m21note
                    fake = m21note.Note(p)
                    fake.quarterLength = n.quarterLength
                    fake.volume        = n.volume
                    notes_edn.append(_midi_note_to_edn(fake, n.offset, bpm))

        # Total bars (quarterLength / beats-per-bar)
        beats_per_bar = float(tsigs[0].numerator) if tsigs else 4.0
        total_beats   = float(score.highestTime)
        total_bars    = int(total_beats / beats_per_bar) + 1

        edn = "[" + " ".join(notes_edn) + "]"
        return {"status":   "ok",
                "notes":    edn,
                "tempo":    bpm,
                "time-sig": tsig,
                "bars":     total_bars}
    except Exception as e:
        return {"status": "error", "message": str(e)}


_HANDLERS = {
    "load":        _handle_load,
    "list":        _handle_list,
    "search":      _handle_search,
    "load-work":   _handle_load_work,
    "ping":        _handle_ping,
    "shutdown":    _handle_shutdown,
    "parse-midi":  _handle_parse_midi,
}

# ---------------------------------------------------------------------------
# Request dispatch — shared by stdio and socket modes
# ---------------------------------------------------------------------------

def _run_loop(reader, writer_fn):
    """Drive the JSON-lines request loop.

    reader    — iterable of text lines (sys.stdin or socket file)
    writer_fn — callable(str) that writes one response line and flushes
    """
    for raw_line in reader:
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        try:
            req = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            writer_fn(json.dumps({"status": "error", "message": f"bad JSON: {exc}"}))
            continue

        op = req.get("op", "")
        handler = _HANDLERS.get(op)
        resp = handler(req) if handler else {"status": "error", "message": f"unknown op: {op!r}"}

        req_id = req.get("id")
        if req_id is not None:
            resp["id"] = req_id

        shutdown = resp.pop("__shutdown", False)
        writer_fn(json.dumps(resp))
        if shutdown:
            break


# ---------------------------------------------------------------------------
# Main — stdio (default) or Unix domain socket (--socket <path>)
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="nous music21 server")
    parser.add_argument("--socket", default=None, metavar="PATH",
                        help="Unix domain socket path; when set, accepts one connection "
                             "instead of reading from stdin")
    args = parser.parse_args()

    if args.socket:
        # Socket mode: BEAM supervises the process; nous.m21 connects as client.
        # Stale socket from a previous crash is removed before binding.
        try:
            os.unlink(args.socket)
        except FileNotFoundError:
            pass

        srv = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
        srv.bind(args.socket)
        srv.listen(1)

        # Signal readiness to the Port (BEAM reads stdout).
        print(json.dumps({"status": "ready", "socket": args.socket}), flush=True)

        conn, _ = srv.accept()
        reader  = conn.makefile("r", encoding="utf-8")
        # writer_fn closes over the connection for flushing
        wfile   = conn.makefile("w", encoding="utf-8")
        def _write(line):
            wfile.write(line + "\n")
            wfile.flush()

        try:
            _run_loop(reader, _write)
        finally:
            conn.close()
            srv.close()
    else:
        # Stdio mode: legacy / standalone use.
        # Exits on stdin EOF (parent JVM died — OS pipe crash detection).
        def _write(line):
            print(line, flush=True)
        _run_loop(sys.stdin, _write)


if __name__ == "__main__":
    main()
