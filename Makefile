# Makefile — Seed v0.1 dev workflow (Linux).
#
# Quick start for a new dev:
#   make install    # one-time: install Android SDK, emulator, AVD
#   make build      # build the debug APK
#   make run        # start emulator, install APK, launch app
#   make backend    # start dev backend in the background
#   make stop       # stop the emulator and the backend
#   make test       # run backend tests
#   make clean      # clean android build outputs and python caches
#
# Override the SDK location with ANDROID_HOME=/path/to/sdk on the
# command line or in your environment. Default: $$HOME/android-sdk.
#
# This Makefile is intentionally simple — every target is a short
# sequence of shell commands with a clear status message. No clever
# abstractions, no auto-generated wrappers.

# ---- Configuration ---------------------------------------------------------

# Respect ANDROID_HOME if set (env or command-line `make ANDROID_HOME=...`),
# otherwise default to ~/android-sdk. The `?=` operator is what makes
# this work without warnings: it sets the variable only when unset.
ANDROID_HOME ?= $(HOME)/android-sdk
export ANDROID_HOME

# Versions pinned to match the app's build.gradle.kts.
ANDROID_PLATFORM    := android-34
ANDROID_BUILD_TOOLS := 34.0.0
SYSTEM_IMAGE       := system-images;android-34;default;x86_64
AVD_NAME           := seed_dev

# GPU mode for the emulator. The default `auto` resolves to
# `host` (Vulkan passthrough) on systems with a discrete GPU,
# which crashes silently on some dual-GPU setups (Intel +
# NVIDIA, AMD + NVIDIA, etc.) — the qemu process dies a
# few seconds after `Boot completed`, before adb ever
# registers the device. `swiftshader` is pure software
# rendering: slower, but stable across all GPU configs.
# Note: `swiftshader_indirect` still uses the host GPU and
# inherits the crash. Override on the command line with
# `make run EMULATOR_GPU=host` if you have working drivers.
EMULATOR_GPU ?= swiftshader

# Pin AVD location inside ANDROID_HOME so it's deterministic across
# machines (newer cmdline-tools default to $XDG_CONFIG_HOME/.android/avd,
# which is harder to reference from a Makefile).
ANDROID_AVD_HOME   := $(ANDROID_HOME)/avd
export ANDROID_AVD_HOME

# Tool locations inside the SDK.
SDKMANAGER := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager
AVDMANAGER := $(ANDROID_HOME)/cmdline-tools/latest/bin/avdmanager
EMULATOR   := $(ANDROID_HOME)/emulator/emulator
ADB        := $(ANDROID_HOME)/platform-tools/adb

# App identity (mirrors build.gradle.kts).
APK          := android/app/build/outputs/apk/debug/app-debug.apk
APP_ID       := com.seed.app
APP_ACTIVITY := com.seed.app.MainActivity

# Process bookkeeping.
BACKEND_LOG  := backend.log
BACKEND_PID  := backend.pid
EMULATOR_LOG := emulator.log
EMULATOR_PID := emulator.pid

# Boot wait timeout (first boot can take 60-120s).
BOOT_TIMEOUT := 180

# cmdline-tools (Linux). Bump via this single URL when a new
# stable is needed.
CMDLINE_TOOLS_URL := https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

# ---- Phony entry points ----------------------------------------------------

.PHONY: help
help:  ## show this help (auto-generated)
	@awk 'BEGIN {printf "Seed v0.1 dev Makefile\n\nUsage: make <target>\n\nTargets:\n"} \
		/^[a-zA-Z_-]+:.*##/ { \
			target = $$1; sub(/:$$/, "", target); \
			desc = $$0; sub(/^[^#]*##[ \t]*/, "", desc); \
			printf "  \033[36m%-12s\033[0m %s\n", target, desc; \
		}' $(MAKEFILE_LIST)

.PHONY: install
install: check-deps cmdline-tools licenses sdk-packages avd  ## one-time: install Android SDK, emulator, AVD
	@echo ""
	@echo "Install complete. ANDROID_HOME=$(ANDROID_HOME)"
	@echo "AVD '$(AVD_NAME)' is ready."
	@echo "Next: \`make build\` then \`make run\`."

.PHONY: build
build: $(APK)  ## build the debug APK

# `make run` depends on the APK file (not the build phony) so we
# only rebuild when something actually changed. The recipe for
# $(APK) below is the canonical build step.
.PHONY: run
run: check-deps $(APK)  ## start emulator, install APK, launch app
	@if [ ! -d $(ANDROID_AVD_HOME)/$(AVD_NAME).avd ]; then \
		echo "!! AVD '$(AVD_NAME)' not found. Run \`make install\` first."; \
		exit 1; \
	fi
	@if [ -f $(EMULATOR_PID) ] && kill -0 $$(cat $(EMULATOR_PID)) 2>/dev/null; then \
		echo ">> Emulator already running (pid $$(cat $(EMULATOR_PID)))."; \
	else \
		echo ">> Starting emulator '$(AVD_NAME)' (log: $(EMULATOR_LOG))..."; \
		rm -f $(EMULATOR_PID); \
		# `< /dev/null` + `setsid` so the emulator is fully
		# detached from make's controlling terminal (no
		# SIGINT propagation if the user Ctrl-C's the boot
		# loop). nohup alone is enough to ignore SIGHUP,
		# but on a noisy TTY SIGINT is the more common
		# kill signal. `-gpu $(EMULATOR_GPU)` forces a
		# software renderer; see EMULATOR_GPU above.
		setsid nohup $(EMULATOR) -avd $(AVD_NAME) \
			-no-snapshot-load -no-audio -gpu $(EMULATOR_GPU) \
			> $(EMULATOR_LOG) 2>&1 < /dev/null & \
		echo $$! > $(EMULATOR_PID); \
		sleep 2; \
		if ! kill -0 $$(cat $(EMULATOR_PID)) 2>/dev/null; then \
			echo "!! Emulator failed to start. Check $(EMULATOR_LOG)."; \
			rm -f $(EMULATOR_PID); \
			exit 1; \
		fi; \
	fi
	@echo ">> Waiting for adb to see the device..."
	@$(ADB) start-server >/dev/null 2>&1
	@# `wait-for-device` blocks indefinitely if adb never
	@# sees the emulator. Cap it at 30s so a slow / crashed
	@# emulator surfaces a clear error instead of hanging
	@# the Makefile until the user Ctrl-C's.
	@if ! timeout 30 $(ADB) wait-for-device; then \
		echo "!! adb never saw the emulator after 30s. Tail of $(EMULATOR_LOG):"; \
		tail -20 $(EMULATOR_LOG) | sed 's/^/    /'; \
		exit 1; \
	fi
	@echo ">> Waiting for boot to complete (up to $(BOOT_TIMEOUT)s)..."
	@i=0; while [ "$$($(ADB) shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do \
		i=$$((i+1)); \
		if [ $$i -ge $(BOOT_TIMEOUT) ]; then \
			echo "!! Boot timeout after $(BOOT_TIMEOUT)s. Check $(EMULATOR_LOG)."; \
			exit 1; \
		fi; \
		sleep 1; \
	done
	@echo ">> Installing APK..."
	@$(ADB) install -r $(APK)
	@echo ">> Launching $(APP_ID)/$(APP_ACTIVITY)..."
	@$(ADB) shell am start -n $(APP_ID)/$(APP_ACTIVITY)
	@echo ">> App launched. Attach: \`adb shell\` or \`adb logcat\`."

.PHONY: backend
backend:  ## start dev backend in background (logs: backend.log, pid: backend.pid)
	@if [ -f $(BACKEND_PID) ] && kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		echo ">> Backend already running (pid $$(cat $(BACKEND_PID)))."; \
		exit 0; \
	fi
	@echo ">> Starting backend (uvicorn :7777)..."
	@rm -f $(BACKEND_PID)
	@nohup ./backend/scripts/dev.sh > $(BACKEND_LOG) 2>&1 & \
	echo $$! > $(BACKEND_PID)
	@sleep 1
	@if kill -0 $$(cat $(BACKEND_PID)) 2>/dev/null; then \
		echo ">> Backend running (pid $$(cat $(BACKEND_PID))), log: $(BACKEND_LOG)"; \
	else \
		echo "!! Backend failed to start. See $(BACKEND_LOG)."; \
		rm -f $(BACKEND_PID); \
		exit 1; \
	fi

.PHONY: stop
stop:  ## stop the emulator and the backend
	@echo ">> Stopping backend..."
	@if [ -f $(BACKEND_PID) ]; then \
		kill $$(cat $(BACKEND_PID)) 2>/dev/null || true; \
		rm -f $(BACKEND_PID); \
	else \
		ps -ef | grep '[s]eed_backend.service:app' | awk '{print $$2}' | xargs -r kill 2>/dev/null || true; \
	fi
	@echo ">> Stopping emulator..."
	@if [ -f $(EMULATOR_PID) ] && kill -0 $$(cat $(EMULATOR_PID)) 2>/dev/null; then \
		kill $$(cat $(EMULATOR_PID)) 2>/dev/null || true; \
		rm -f $(EMULATOR_PID); \
	else \
		$(ADB) emu kill 2>/dev/null || true; \
	fi
	@echo ">> Stopped."

.PHONY: test
test:  ## run backend + webapp tests (from repo root so both suites are picked up)
	@.venv/bin/python -m pytest backend/ webapp/

.PHONY: clean
clean:  ## clean android build outputs, python caches, logs
	@rm -rf android/app/build android/build android/.gradle android/app/.gradle
	@find backend webapp \( -type d -name __pycache__ -o -type d -name .pytest_cache \) -prune -exec rm -rf {} +
	@rm -f $(BACKEND_LOG) $(BACKEND_PID) $(EMULATOR_LOG) $(EMULATOR_PID)
	@rm -rf .tmp
	@echo ">> Cleaned."

# ---- Build recipe ----------------------------------------------------------
# Used by both `make build` and `make run` (the latter only invokes
# it if the APK is missing or out of date).

$(APK):
	@echo ">> Building debug APK..."
	@cd android && ./gradlew :app:assembleDebug
	@echo ">> APK ready: $@"

# ---- Install helpers -------------------------------------------------------
# Each prerequisite is independently idempotent: skipping the work
# when the artifact already exists is the whole point. They print
# one line so a new dev can see exactly which step did what.

.PHONY: check-deps
check-deps:
	@command -v java >/dev/null || { \
		echo "!! java not found. Install: sudo apt install openjdk-17-jdk"; exit 1; }
	@command -v curl >/dev/null || { \
		echo "!! curl not found. Install: sudo apt install curl"; exit 1; }
	@command -v unzip >/dev/null || { \
		echo "!! unzip not found. Install: sudo apt install unzip"; exit 1; }
	@[ -e /dev/kvm ] || { \
		echo "!! /dev/kvm not found. The emulator needs KVM."; \
		echo "   On a VM, enable nested virtualization."; \
		echo "   On bare metal: sudo usermod -aG kvm $$USER && newgrp kvm."; \
		exit 1; }

.PHONY: cmdline-tools
cmdline-tools:
	@if [ -x $(SDKMANAGER) ]; then \
		echo ">> cmdline-tools already installed at $(ANDROID_HOME)/cmdline-tools/latest"; \
	else \
		echo ">> Downloading Android cmdline-tools to $(ANDROID_HOME)..."; \
		set -e; \
		mkdir -p $(CURDIR)/.tmp $(ANDROID_HOME)/cmdline-tools; \
		curl -fsSL -o $(CURDIR)/.tmp/commandlinetools.zip $(CMDLINE_TOOLS_URL); \
		unzip -q -o $(CURDIR)/.tmp/commandlinetools.zip -d $(ANDROID_HOME)/cmdline-tools; \
		mv $(ANDROID_HOME)/cmdline-tools/cmdline-tools $(ANDROID_HOME)/cmdline-tools/latest; \
		rm -rf $(CURDIR)/.tmp; \
		echo ">> cmdline-tools installed."; \
	fi

.PHONY: licenses
licenses:
	@echo ">> Accepting SDK licenses (idempotent)..."
	@yes | $(SDKMANAGER) --licenses >/dev/null

.PHONY: sdk-packages
sdk-packages:
	@echo ">> Installing SDK packages (idempotent)..."
	@$(SDKMANAGER) \
		"platform-tools" \
		"platforms;$(ANDROID_PLATFORM)" \
		"build-tools;$(ANDROID_BUILD_TOOLS)" \
		"emulator" \
		"$(SYSTEM_IMAGE)"

.PHONY: avd
avd:
	@mkdir -p $(ANDROID_AVD_HOME)
	@if [ ! -d $(ANDROID_AVD_HOME)/$(AVD_NAME).avd ]; then \
		echo ">> Creating AVD '$(AVD_NAME)' at $(ANDROID_AVD_HOME)..."; \
		echo "no" | $(AVDMANAGER) create avd -n $(AVD_NAME) -k "$(SYSTEM_IMAGE)" -d pixel; \
		echo ">> AVD created."; \
	fi
	@# Patch the AVD's GPU mode. `avdmanager create avd -d
	@# pixel` bakes in `hw.gpu.mode = auto`, which resolves
	@# to `host` (Vulkan passthrough) on systems with a
	@# discrete GPU and crashes silently on dual-GPU
	@# hardware. We force `swiftshader` (pure software)
	@# here so the AVD works regardless of host GPU
	@# drivers. Idempotent — running `make avd` again is a
	@# no-op if the values are already correct.
	@AVD_CFG=$(ANDROID_AVD_HOME)/$(AVD_NAME).avd/config.ini; \
	if [ ! -f $$AVD_CFG ]; then \
		echo "!! AVD config not found at $$AVD_CFG"; \
		exit 1; \
	fi; \
	sed -i 's/^hw\.gpu\.enabled = .*/hw.gpu.enabled = yes/' $$AVD_CFG; \
	sed -i 's/^hw\.gpu\.mode = .*/hw.gpu.mode = $(EMULATOR_GPU)/' $$AVD_CFG; \
	echo ">> AVD GPU mode set to '$(EMULATOR_GPU)' (in $$AVD_CFG)."
