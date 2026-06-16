# SPDX-License-Identifier: LGPL-2.1-or-later
.PHONY: all build build-link install test clean docs docs-clj docs-cpp docs-guide

BUILD_DIR  ?= build
CMAKE_FLAGS ?= -DCMAKE_BUILD_TYPE=Release
PREFIX     ?= $(HOME)/.local/nomos-studio

# ---------------------------------------------------------------------------
# Default target
# ---------------------------------------------------------------------------

all: build

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

build: build-clj build-cpp

build-clj:
	lein compile

build-cpp: $(BUILD_DIR)/Makefile
	cmake --build $(BUILD_DIR) --parallel

$(BUILD_DIR)/Makefile:
	cmake -S . -B $(BUILD_DIR) $(CMAKE_FLAGS)

build-link:
	cmake -S . -B $(BUILD_DIR) $(CMAKE_FLAGS) -DNOUS_ENABLE_LINK=ON
	cmake --build $(BUILD_DIR) --parallel

uberjar:
	lein uberjar

# ---------------------------------------------------------------------------
# Install — copies nous-sidecar binary and nous standalone jar to PREFIX
# ---------------------------------------------------------------------------

install: uberjar build-cpp
	install -d $(PREFIX)/bin $(PREFIX)/lib/nous
	install -m 755 $(BUILD_DIR)/cpp/nous-sidecar/nous-sidecar $(PREFIX)/bin/nous-sidecar
	install -m 644 target/uberjar/nous-$(shell lein version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')-standalone.jar \
	    $(PREFIX)/lib/nous/nous-standalone.jar

# ---------------------------------------------------------------------------
# Test
# ---------------------------------------------------------------------------

test: test-clj

test-clj:
	lein test

test-cpp: $(BUILD_DIR)/Makefile
	cmake --build $(BUILD_DIR) --target nous-tests --parallel
	ctest --test-dir $(BUILD_DIR) --output-on-failure

# ---------------------------------------------------------------------------
# Documentation
# ---------------------------------------------------------------------------

docs: docs-clj docs-cpp docs-guide

docs-clj:
	lein codox

docs-cpp: $(BUILD_DIR)/Makefile
	doxygen cpp/Doxyfile

docs-guide:
	mdbook build doc/book

# ---------------------------------------------------------------------------
# Clean
# ---------------------------------------------------------------------------

clean: clean-clj clean-cpp

clean-clj:
	lein clean

clean-cpp:
	rm -rf $(BUILD_DIR)

clean-all: clean
	rm -rf target/codox
