#!/usr/bin/env bash
# 生成 RAG 离线评测基线：不连数据库、不调模型，纯本地计算。
# 用法：bash perf/rag/run-offline-eval.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

if [[ ! -f "perf/rag/eval-set-v1.jsonl" ]]; then
  echo "未找到 perf/rag/eval-set-v1.jsonl，请在仓库根目录执行" >&2
  exit 1
fi

echo "==> 运行 RAG 离线评测（评测集 30 条）"
./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.eval.RagOfflineEvaluationTest'

echo "==> 基线已生成: perf/rag/baseline-offline.md"
