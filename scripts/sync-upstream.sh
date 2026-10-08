#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "$(git status --porcelain)" ]]; then
    echo '请先提交或暂存当前改动，确认工作区干净后再同步。' >&2
    exit 1
fi
if ! git remote get-url upstream >/dev/null 2>&1; then
    echo '缺少 upstream，请先按 docs/HONGQI_UPSTREAM_SYNC.md 配置上游。' >&2
    exit 1
fi
branch="sync-upstream/$(date -u +%Y%m%d-%H%M%S)"
git fetch upstream
git switch -c "$branch"
if ! git merge --no-ff --no-edit upstream/main; then
    echo '合并存在冲突。当前保留在同步分支，请解决冲突或执行 git merge --abort。' >&2
    exit 1
fi
echo "上游已合入 $branch。请执行测试和构建，验证后再合入 hongqi-integration。"
