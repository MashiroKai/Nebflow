#!/bin/bash
# 保存当前分支进度，然后切换到 main 分支
# 用法: bash goto-main.sh

set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_DIR"

CURRENT_BRANCH=$(git branch --show-current)

if [ "$CURRENT_BRANCH" = "main" ]; then
    echo "已经在 main 分支上了，无需切换。"
    exit 0
fi

echo "当前分支: $CURRENT_BRANCH"

# 检查是否有未保存的更改
if [ -n "$(git status --porcelain)" ]; then
    STASH_NAME="auto-save-$CURRENT_BRANCH-$(date +%Y%m%d-%H%M%S)"
    echo "检测到未保存的更改，执行 git stash push ..."
    git stash push -m "$STASH_NAME"
    echo "已保存到 stash: $STASH_NAME"
else
    echo "工作区干净，无需 stash。"
fi

echo "切换到 main 分支 ..."
git checkout main

echo ""
echo "完成！已从 $CURRENT_BRANCH 切换到 main。"
echo "如需恢复之前的工作，运行："
echo "  git checkout $CURRENT_BRANCH && git stash pop"
