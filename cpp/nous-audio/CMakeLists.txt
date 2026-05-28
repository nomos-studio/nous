# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# nous-audio — CLAP / audio binary
#
# Hosts CLAP plugins; provides direct Eurorack CV via Expert Sleepers ES-9
# or compatible DC-coupled interface. Uses libnous-rt for scheduling.
#
# NOTE: If nous-audio is ever built with Ableton Link, the combined binary
# becomes GPL-2.0-or-later. See doc/licensing.md for rationale.
# ---------------------------------------------------------------------------

add_executable(nous-audio
  src/main.cpp
  src/clap_host.cpp
  src/audio_io.cpp
)

target_link_libraries(nous-audio
  PRIVATE nous-rt
)

set_target_properties(nous-audio PROPERTIES
  CXX_STANDARD 17
)
