#!/usr/bin/env bash
# =============================================================================
#  Mobile Remote: developer environment setup
#
#  Installs everything needed to build the app from the command line:
#    - JDK 17
#    - Android command-line tools (sdkmanager)
#    - Android SDK: platform-tools (adb), platforms;android-35, build-tools;35.0.0
#    - Gradle wrapper (Gradle 8.9) + all Gradle/AGP/Kotlin dependencies
#  Then runs a verification build.
#
#  Supported: macOS (Apple Silicon / Intel, via Homebrew)
#             Linux (Debian/Ubuntu via apt, Fedora via dnf)
#
#  Usage:
#    ./setup-dev.sh              full setup + verification build
#    ./setup-dev.sh --no-build   setup only
#    ./setup-dev.sh --doctor     only check what is installed
# =============================================================================
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$HOME/.mobileremote-env"
JAVA_VERSION=17
GRADLE_VERSION=8.9
SDK_PACKAGES=("platform-tools" "platforms;android-35" "build-tools;35.0.0")
LINUX_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

DO_BUILD=1
DOCTOR_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --doctor)   DOCTOR_ONLY=1 ;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# ---------- output helpers ----------
if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; N=$'\e[0m'; else B=""; G=""; Y=""; R=""; N=""; fi
step() { echo; echo "${B}==> $*${N}"; }
ok()   { echo "  ${G}✓${N} $*"; }
warn() { echo "  ${Y}!${N} $*"; }
fail() { echo "  ${R}✗ $*${N}"; exit 1; }

OS="$(uname -s)"
ARCH="$(uname -m)"

# ---------- locate tools ----------
find_java_home() {
  if [ "$OS" = "Darwin" ]; then
    if command -v brew >/dev/null && [ -d "$(brew --prefix)/opt/openjdk@$JAVA_VERSION" ]; then
      echo "$(brew --prefix)/opt/openjdk@$JAVA_VERSION/libexec/openjdk.jdk/Contents/Home"; return
    fi
    /usr/libexec/java_home -v "$JAVA_VERSION" 2>/dev/null && return
  else
    for d in /usr/lib/jvm/java-$JAVA_VERSION-openjdk* /usr/lib/jvm/temurin-$JAVA_VERSION* /usr/lib/jvm/java-$JAVA_VERSION*; do
      [ -x "$d/bin/javac" ] && { echo "$d"; return; }
    done
  fi
  echo ""
}

default_android_home() {
  if [ "$OS" = "Darwin" ] && command -v brew >/dev/null; then
    echo "$(brew --prefix)/share/android-commandlinetools"
  else
    echo "$HOME/Android/Sdk"
  fi
}

find_sdkmanager() {
  for c in "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$(command -v sdkmanager 2>/dev/null || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
  done
  echo ""
}

# ---------- doctor ----------
doctor() {
  step "Environment check"
  local all=0
  local jh; jh="$(find_java_home)"
  if [ -n "$jh" ]; then ok "JDK $JAVA_VERSION: $jh"; else warn "JDK $JAVA_VERSION: not found"; all=1; fi
  ANDROID_HOME="${ANDROID_HOME:-$(default_android_home)}"
  if [ -n "$(find_sdkmanager)" ]; then ok "sdkmanager: $(find_sdkmanager)"; else warn "sdkmanager: not found"; all=1; fi
  [ -x "$ANDROID_HOME/platform-tools/adb" ] && ok "adb: $ANDROID_HOME/platform-tools/adb" || { warn "adb: not found"; all=1; }
  [ -d "$ANDROID_HOME/platforms/android-35" ] && ok "Android SDK platform 35" || { warn "Android SDK platform 35: missing"; all=1; }
  [ -d "$ANDROID_HOME/build-tools/35.0.0" ] && ok "Build-tools 35.0.0" || { warn "Build-tools 35.0.0: missing"; all=1; }
  [ -x "$PROJECT_DIR/gradlew" ] && ok "Gradle wrapper" || { warn "Gradle wrapper: missing"; all=1; }
  [ -f "$ENV_FILE" ] && ok "Env file: $ENV_FILE" || { warn "Env file: missing"; all=1; }
  return $all
}

if [ "$DOCTOR_ONLY" = 1 ]; then
  if doctor; then echo; echo "${G}Everything is installed.${N}"; else echo; echo "Run ./setup-dev.sh to install what's missing."; exit 1; fi
  exit 0
fi

echo "${B}Mobile Remote: developer setup${N}  ($OS $ARCH)"

# ---------- 1. system packages ----------
step "1/6  System packages"
if [ "$OS" = "Darwin" ]; then
  xcode-select -p >/dev/null 2>&1 || { warn "Installing Xcode Command Line Tools (a dialog will open)…"; xcode-select --install || true; }
  if ! command -v brew >/dev/null; then
    warn "Installing Homebrew…"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    eval "$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)"
  fi
  ok "Homebrew $(brew --version | head -1 | awk '{print $2}')"
  brew list --formula openjdk@$JAVA_VERSION >/dev/null 2>&1 || brew install openjdk@$JAVA_VERSION
  brew list --formula gradle >/dev/null 2>&1 || brew install gradle
  brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools
  ok "openjdk@$JAVA_VERSION, gradle, android-commandlinetools"
elif [ "$OS" = "Linux" ]; then
  SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
  if command -v apt-get >/dev/null; then
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq openjdk-$JAVA_VERSION-jdk-headless unzip curl git >/dev/null
  elif command -v dnf >/dev/null; then
    $SUDO dnf install -y -q java-$JAVA_VERSION-openjdk-devel unzip curl git
  else
    fail "Unsupported Linux distribution: install JDK $JAVA_VERSION, unzip, and curl, then run again"
  fi
  ok "JDK $JAVA_VERSION, unzip, curl, git"
else
  fail "Unsupported OS: $OS (use macOS or Linux; on Windows use WSL2)"
fi

# ---------- 2. Java ----------
step "2/6  Java $JAVA_VERSION"
JAVA_HOME="$(find_java_home)"
[ -n "$JAVA_HOME" ] || fail "JDK $JAVA_VERSION not found after install"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
ok "$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---------- 3. Android command-line tools ----------
step "3/6  Android command-line tools"
export ANDROID_HOME="${ANDROID_HOME:-$(default_android_home)}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
mkdir -p "$ANDROID_HOME"
if [ "$OS" = "Linux" ] && [ -z "$(find_sdkmanager)" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$LINUX_CMDLINE_TOOLS_URL"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
SDKMANAGER="$(find_sdkmanager)"
[ -n "$SDKMANAGER" ] || fail "sdkmanager not found"
ok "$SDKMANAGER"

# ---------- 4. Android SDK packages ----------
step "4/6  Android SDK packages"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${SDK_PACKAGES[@]}" 2>&1 | grep -v "^\[=*" | grep -vi "deprecated\|android cli\|d.android.com\|^$" || true
for p in "${SDK_PACKAGES[@]}"; do ok "$p"; done
echo "sdk.dir=$ANDROID_HOME" > "$PROJECT_DIR/local.properties"
ok "local.properties"

# ---------- 5. shell environment ----------
step "5/6  Shell environment"
cat > "$ENV_FILE" <<EOF
# Added by Mobile Remote setup-dev.sh
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF
ok "Wrote $ENV_FILE"
for rc in "$HOME/.zshrc" "$HOME/.bashrc"; do
  if [ -f "$rc" ] || [ "$(basename "${SHELL:-}")" = "$(basename "$rc" | sed 's/^\.//;s/rc$//')" ]; then
    touch "$rc"
    grep -q "mobileremote-env" "$rc" || echo '[ -f "$HOME/.mobileremote-env" ] && source "$HOME/.mobileremote-env"' >> "$rc"
    ok "Linked from $rc"
  fi
done
export PATH="$ANDROID_HOME/platform-tools:$PATH"

# ---------- 6. Gradle wrapper + dependencies ----------
step "6/6  Gradle $GRADLE_VERSION and project dependencies"
cd "$PROJECT_DIR"
if [ ! -x ./gradlew ]; then
  if command -v gradle >/dev/null; then
    gradle wrapper --gradle-version "$GRADLE_VERSION" -q
  else
    fail "Gradle not found to generate the wrapper"
  fi
fi
chmod +x ./gradlew
./gradlew --version -q >/dev/null
ok "Gradle wrapper ready"
./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q >/dev/null
ok "Android Gradle Plugin, Kotlin, and dependencies downloaded"

# ---------- verification build ----------
if [ "$DO_BUILD" = 1 ]; then
  step "Verification build"
  ./gradlew assembleDebug -q
  cp app/build/outputs/apk/debug/app-debug.apk ./MobileRemote.apk
  ok "Built $(pwd)/MobileRemote.apk ($(du -h MobileRemote.apk | cut -f1))"
fi

doctor || true
echo
echo "${G}${B}Setup complete.${N}"
echo "  Open a new terminal (or run: source $ENV_FILE)"
echo "  Build:    ./build.sh"
echo "  Install:  ./build.sh install      (phones connected over adb)"
echo "  Devices:  adb devices"
