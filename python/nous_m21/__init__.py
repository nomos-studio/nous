# SPDX-License-Identifier: LGPL-2.1-or-later
"""
nous_m21 — Music21 sidecar for nous.

Runs as a subprocess; receives batch requests from the nous JVM process
via stdin (EDN-like JSON), responds on stdout. Amortizes Music21 startup
cost across multiple calls (Q22).

Usage (from nous JVM):
    Process is launched once and kept alive. Calls are serialized JSON lines.
"""
