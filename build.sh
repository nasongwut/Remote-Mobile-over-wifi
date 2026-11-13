#!/bin/bash
# สร้าง APK ของ Mobile Remote ด้วย command line ล้วน (ไม่ต้องใช้ Android Studio)
# ใช้:  ./build.sh            -> build อย่างเดียว
#       ./build.sh install    -> build แล้วติดตั้งลงมือถือที่ต่อ adb อยู่ทุกเครื่อง
set -e
cd "$(dirname "$0")"

# 1) Homebrew
if ! command -v brew >/dev/null; then
  echo ">> ติดตั้ง Homebrew"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  eval "$(/opt/homebrew/bin/brew shellenv 2>/dev/null || /usr/local/bin/brew shellenv)"
fi

# 2) Java 17, Gradle, Android command-line tools, adb
brew list openjdk@17 >/dev/null 2>&1 || brew install openjdk@17
brew list gradle     >/dev/null 2>&1 || brew install gradle
brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools
brew list --cask android-platform-tools  >/dev/null 2>&1 || brew install --cask android-platform-tools

export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"

# 3) Android SDK
if [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
  echo ">> ติดตั้ง Android SDK 35"
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null
  sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0"
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 4) Gradle wrapper (ล็อกเวอร์ชัน 8.9 ให้เข้ากับ Android Gradle Plugin)
[ -f gradlew ] || gradle wrapper --gradle-version 8.9 -q
chmod +x gradlew

# 5) Build
echo ">> กำลัง build APK"
./gradlew assembleDebug -q
APK="app/build/outputs/apk/debug/app-debug.apk"
cp "$APK" ./MobileRemote.apk
echo ">> เสร็จ: $(pwd)/MobileRemote.apk"

# 6) ติดตั้งผ่าน adb (ถ้าสั่ง ./build.sh install)
if [ "$1" = "install" ]; then
  for d in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    echo ">> ติดตั้งลง $d"
    adb -s "$d" install --user 0 -r MobileRemote.apk
  done
fi
