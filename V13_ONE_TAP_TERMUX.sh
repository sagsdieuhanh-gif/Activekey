#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="sagsdieuhanh-gif/Activekey"
DOWNLOAD="/storage/emulated/0/Download"
WORK="$HOME/.trungkien_v132_full_deploy"
REPO_DIR="$HOME/Activekey"
ARTIFACT="TRUNGKIEN-V13.2-debug-apk"

say(){ printf '\n\033[1;36m%s\033[0m\n' "$*"; }
ok(){ printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

say "TRUNGKIEN V13.2 FULL ADAS — ONE TAP DEPLOY + BUILD"

[ -d "$DOWNLOAD" ] || { termux-setup-storage || true; sleep 2; }
[ -d "$DOWNLOAD" ] || die "Termux chưa truy cập được bộ nhớ điện thoại."

pkg update -y >/dev/null
pkg install -y git gh unzip findutils >/dev/null
ok "Đã có git / gh / unzip."

if ! gh auth status >/dev/null 2>&1; then
  say "Đăng nhập GitHub — chỉ cần làm lần đầu."
  gh auth login --hostname github.com --git-protocol https --web
fi
gh auth setup-git >/dev/null 2>&1 || true
ok "GitHub đã đăng nhập."

rm -rf "$WORK"
mkdir -p "$WORK"
SRC=""

is_v132_project() {
  local d="$1"
  [ -d "$d/app" ] && [ -f "$d/settings.gradle.kts" ] &&
    grep -q 'versionName = "13.2.0"' "$d/app/build.gradle.kts" 2>/dev/null
}

for d in "$DOWNLOAD/TRUNGKIEN_V13.2.0_FULL_ADAS" "$DOWNLOAD/V13.2" "$DOWNLOAD/TRUNGKIEN_V13" "$DOWNLOAD/V13"; do
  if is_v132_project "$d"; then SRC="$d"; break; fi
done

if [ -z "$SRC" ]; then
  ZIP="$(find "$DOWNLOAD" -maxdepth 1 -type f \( -iname 'TRUNGKIEN_V13.2*.zip' -o -iname 'V13.2*.zip' \) -printf '%T@ %p\n' 2>/dev/null | sort -nr | cut -d' ' -f2- | head -n 1 || true)"
  [ -n "$ZIP" ] || die "Không thấy ZIP V13.2 FULL trong Download. Hãy tải BUILD READY trước."
  say "Giải nén $(basename "$ZIP")..."
  unzip -q -o "$ZIP" -d "$WORK/unzip"
  PROJECT_GRADLE="$(find "$WORK/unzip" -maxdepth 5 -type f -name settings.gradle.kts -print | head -n 1 || true)"
  [ -n "$PROJECT_GRADLE" ] || die "ZIP V13.2 không có settings.gradle.kts."
  SRC="$(dirname "$PROJECT_GRADLE")"
fi

is_v132_project "$SRC" || die "Source tìm thấy không phải V13.2.0 FULL."
[ -f "$SRC/.github/workflows/android.yml" ] || die "Source V13.2 thiếu workflow Android."
ok "Source V13.2: $SRC"

cd "$HOME"
if [ ! -d "$REPO_DIR/.git" ]; then
  gh repo clone "$REPO" "$REPO_DIR"
fi
cd "$REPO_DIR"
git checkout main >/dev/null 2>&1 || true
git pull origin main

git config --global user.name "sagsdieuhanh-gif"
git config --global user.email "mobile-build@users.noreply.github.com"

# Keep rollback points once. Never overwrite existing stable refs.
if ! git ls-remote --exit-code --heads origin v12-stable >/dev/null 2>&1; then
  git branch v12-stable HEAD
  git push origin v12-stable
  ok "Đã giữ bản trước tại v12-stable."
fi
if ! git ls-remote --exit-code --heads origin pre-v13.2-stable >/dev/null 2>&1; then
  git branch pre-v13.2-stable HEAD
  git push origin pre-v13.2-stable
  ok "Đã tạo rollback pre-v13.2-stable."
fi

# Preserve separate Admin Key Android project/workflow. It must never be merged into the user APK.
rm -rf "$WORK/preserve"
mkdir -p "$WORK/preserve"
[ -d admin-key-android ] && cp -a admin-key-android "$WORK/preserve/" || true
[ -f .github/workflows/admin-key-android.yml ] && cp -a .github/workflows/admin-key-android.yml "$WORK/preserve/admin-key-android.yml" || true

say "Đưa V13.2 FULL vào main..."
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp -a "$SRC"/. .
chmod +x gradlew || true

if [ -d "$WORK/preserve/admin-key-android" ]; then cp -a "$WORK/preserve/admin-key-android" .; fi
if [ -f "$WORK/preserve/admin-key-android.yml" ]; then
  mkdir -p .github/workflows
  cp -a "$WORK/preserve/admin-key-android.yml" .github/workflows/admin-key-android.yml
fi

for p in app gradle .github gradlew build.gradle.kts settings.gradle.kts; do
  [ -e "$p" ] || die "Thiếu $p sau khi copy V13.2."
done

grep -q 'versionName = "13.2.0"' app/build.gradle.kts || die "Version source không đúng 13.2.0."

# Security sanity check: source user APK must not contain an admin private key.
if grep -R -q 'BEGIN .*PRIVATE KEY' app .github 2>/dev/null; then
  die "PHÁT HIỆN PRIVATE KEY trong source. Dừng deploy để bảo vệ key Admin."
fi

git add -A
CHANGED=1
if git diff --cached --quiet; then
  CHANGED=0
  warn "Repo đã có đúng source V13.2; sẽ chạy workflow thủ công."
else
  git commit -m "TRUNGKIEN V13.2.0 FULL ADAS complete tracking range cut-in thermal sign debug"
  git push origin main
  ok "Đã push V13.2 FULL lên GitHub."
fi

sleep 8
if [ "$CHANGED" -eq 0 ]; then
  gh workflow run android.yml --repo "$REPO" --ref main || true
  sleep 5
fi

RUN_ID="$(gh run list --workflow android.yml --repo "$REPO" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
[ -n "$RUN_ID" ] || die "Chưa lấy được Run ID. Vào GitHub > Actions để kiểm tra."

say "GITHUB ĐANG BUILD V13.2 FULL — RUN $RUN_ID"
if gh run watch "$RUN_ID" --repo "$REPO" --exit-status; then
  ok "Build V13.2 SUCCESS."
else
  die "Build V13.2 FAILURE. Gửi Run ID $RUN_ID cho ChatGPT để đọc log và sửa."
fi

APK_DIR="$DOWNLOAD/V13_2_APK"
rm -rf "$APK_DIR"
mkdir -p "$APK_DIR"

say "Tải APK về Download/V13_2_APK..."
SUCCESS=0
for n in 1 2 3; do
  if gh run download "$RUN_ID" --repo "$REPO" -n "$ARTIFACT" -D "$APK_DIR"; then
    SUCCESS=1
    break
  fi
  warn "Mạng bị ngắt khi tải APK (lần $n/3). Thử lại..."
  rm -rf "$APK_DIR"/*
  sleep 5
done

echo
if [ "$SUCCESS" -eq 1 ]; then
  ok "HOÀN TẤT — APK nằm trong Download/V13_2_APK"
  find "$APK_DIR" -type f -name '*.apk' -print
else
  warn "BUILD ĐÃ THÀNH CÔNG nhưng mạng điện thoại không tải hết artifact."
  echo "Run ID: $RUN_ID"
  echo "Gửi Run ID này cho ChatGPT để lấy APK trực tiếp."
fi
