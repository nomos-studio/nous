# SPDX-License-Identifier: LGPL-2.1-or-later
cmake_minimum_required(VERSION 3.20)

# ---------------------------------------------------------------------------
# libnous-rt — shared C++ runtime library
#
# Provides: clock tick delivery, scheduling, OSC/MIDI dispatch plumbing.
# Linked by both nous-sidecar and nous-audio.
# ---------------------------------------------------------------------------

add_library(nous-rt SHARED
  src/clock.cpp
  src/scheduler.cpp
  src/osc.cpp
)

target_include_directories(nous-rt
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
  PRIVATE
    src
)

set_target_properties(nous-rt PROPERTIES
  VERSION   ${PROJECT_VERSION}
  SOVERSION 0
  CXX_STANDARD 17
)

# link_bridge.h is header-only (NullLinkBridge + abstract interface).
# No sources added here; libnous-link (GPL) provides the implementation.
