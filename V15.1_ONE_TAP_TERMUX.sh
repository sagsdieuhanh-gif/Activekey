#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="sagsdieuhanh-gif/Activekey"
DOWNLOAD="/storage/emulated/0/Download"
WORK="$HOME/.trungkien_v15_1_deploy"
REPO_DIR="$HOME/Activekey"
ARTIFACT="TRUNGKIEN-V15.1-debug-apk"

say(){ printf '\n\033[1;36m%s\033[0m\n' "$*"; }
ok(){ printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

say "TRUNGKIEN V15.1 FOCUSED — LANE + FRONT DISTANCE + MOVE-OFF"
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

rm -rf "$WORK"; mkdir -p "$WORK"
ZIP="$(find "$DOWNLOAD" -maxdepth 1 -type f -iname 'TRUNGKIEN_V15.1.0_FOCUSED_LANE_FRONT_DISTANCE_MOVEOFF_BUILD_READY.zip' -print -quit 2>/dev/null || true)"
[ -n "$ZIP" ] || die "Không thấy ZIP V15.1 BUILD READY trong Download."
say "Giải nén $(basename "$ZIP")..."
unzip -q -o "$ZIP" -d "$WORK/unzip"
PROJECT_GRADLE="$(find "$WORK/unzip" -maxdepth 5 -type f -name settings.gradle.kts -print | head -n 1 || true)"
[ -n "$PROJECT_GRADLE" ] || die "ZIP V15.1 không có settings.gradle.kts."
SRC="$(dirname "$PROJECT_GRADLE")"
grep -q 'versionName = "15.1.0"' "$SRC/app/build.gradle.kts" || die "Source không đúng V15.1.0."
grep -q 'versionCode = 1510' "$SRC/app/build.gradle.kts" || die "versionCode không đúng 1510."
ok "Source V15.1: $SRC"

cd "$HOME"
[ -d "$REPO_DIR/.git" ] || gh repo clone "$REPO" "$REPO_DIR"
cd "$REPO_DIR"
git checkout main >/dev/null 2>&1 || true
git pull origin main

git config --global user.name "sagsdieuhanh-gif"
git config --global user.email "mobile-build@users.noreply.github.com"

# Preserve V15.0 rollback once.
if ! git ls-remote --exit-code --heads origin v15.0-stable >/dev/null 2>&1; then
  git branch v15.0-stable HEAD
  git push origin v15.0-stable
  ok "Đã tạo rollback v15.0-stable."
fi

rm -rf "$WORK/preserve"; mkdir -p "$WORK/preserve"
[ -d admin-key-android ] && cp -a admin-key-android "$WORK/preserve/" || true
[ -f .github/workflows/admin-key-android.yml ] && cp -a .github/workflows/admin-key-android.yml "$WORK/preserve/admin-key-android.yml" || true

say "Đưa V15.1 vào main..."
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp -a "$SRC"/. .
chmod +x gradlew || true
[ -d "$WORK/preserve/admin-key-android" ] && cp -a "$WORK/preserve/admin-key-android" . || true
if [ -f "$WORK/preserve/admin-key-android.yml" ]; then
  mkdir -p .github/workflows
  cp -a "$WORK/preserve/admin-key-android.yml" .github/workflows/admin-key-android.yml
fi

for p in app gradle .github gradlew build.gradle.kts settings.gradle.kts; do [ -e "$p" ] || die "Thiếu $p"; done
if grep -R -q 'BEGIN .*PRIVATE KEY' app .github 2>/dev/null; then die "PHÁT HIỆN PRIVATE KEY trong source user APK."; fi

git add -A
CHANGED=1
if git diff --cached --quiet; then
  CHANGED=0
  warn "Repo đã có V15.1; sẽ chạy workflow thủ công."
else
  git commit -m "TRUNGKIEN V15.1 focused lane front distance move-off"
  git push origin main
  ok "Đã push V15.1 lên GitHub."
fi

sleep 8
if [ "$CHANGED" -eq 0 ]; then
  gh workflow run android.yml --repo "$REPO" --ref main || true
  sleep 5
fi
RUN_ID="$(gh run list --workflow android.yml --repo "$REPO" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
[ -n "$RUN_ID" ] || die "Chưa lấy được Run ID."
say "GITHUB ĐANG BUILD V15.1 — RUN $RUN_ID"

# Mobile networks sometimes abort the GitHub API connection. Do not mislabel that as a build failure.
FINAL=""
for attempt in 1 2 3 4 5; do
  if gh run watch "$RUN_ID" --repo "$REPO" --exit-status; then
    FINAL="success"; break
  fi
  CONCLUSION="$(gh run view "$RUN_ID" --repo "$REPO" --json status,conclusion --jq '(.status // "") + ":" + (.conclusion // "")' 2>/dev/null || true)"
  case "$CONCLUSION" in
    completed:success) FINAL="success"; break ;;
    completed:failure|completed:cancelled|completed:timed_out) FINAL="failure"; break ;;
    *) warn "Mạng/API GitHub gián đoạn (lần $attempt/5), build có thể vẫn đang chạy. Chờ 10 giây..."; sleep 10 ;;
  esac
done
[ "$FINAL" = "success" ] || { [ "$FINAL" = "failure" ] && die "Build V15.1 thật sự thất bại. Run $RUN_ID"; die "Chưa đọc được trạng thái cuối. Run $RUN_ID vẫn có thể đang chạy."; }
ok "Build V15.1 SUCCESS."

APK_DIR="$DOWNLOAD/V15_1_APK"
rm -rf "$APK_DIR"; mkdir -p "$APK_DIR"
say "Tải APK về Download/V15_1_APK..."
SUCCESS=0
for n in 1 2 3; do
  if gh run download "$RUN_ID" --repo "$REPO" -n "$ARTIFACT" -D "$APK_DIR"; then SUCCESS=1; break; fi
  warn "Mạng ngắt khi tải APK (lần $n/3)."; rm -rf "$APK_DIR"/*; sleep 5
done
if [ "$SUCCESS" -eq 1 ]; then
  ok "HOÀN TẤT — APK nằm trong Download/V15_1_APK"
  find "$APK_DIR" -type f -name '*.apk' -print
else
  warn "BUILD SUCCESS nhưng chưa tải được artifact. Run ID: $RUN_ID"
fi
