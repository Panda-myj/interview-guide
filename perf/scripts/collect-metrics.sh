#!/usr/bin/env bash
# 采集本地开发环境性能指标快照：PostgreSQL、Redis、应用 Actuator、宿主机资源。
# 用法: perf/scripts/collect-metrics.sh <输出文件>
# 说明: 依赖本地 psql / redis-cli / curl；Docker 未运行时自动跳过并记录原因。
set -uo pipefail

OUT_FILE="${1:-perf/baseline/metrics-$(date +%Y%m%d-%H%M%S).txt}"
mkdir -p "$(dirname "$OUT_FILE")"

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_DB="${POSTGRES_DB:-interview_guide}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
APP_URL="${APP_URL:-http://localhost:8080}"
ACTUATOR_PATHS="${ACTUATOR_PATHS:-health}"

{
  echo "# 性能指标快照"
  echo "时间: $(date -Iseconds)"
  echo "环境: 本地开发模式（不含生产服务）"
  echo

  echo "## 应用"
  if curl -sf --max-time 3 "$APP_URL/actuator/health" >/dev/null 2>&1; then
    for path in $ACTUATOR_PATHS; do
      echo "### /actuator/$path"
      curl -s --max-time 3 "$APP_URL/actuator/$path" || echo "采集失败"
      echo
    done
  else
    echo "应用未就绪或 Actuator 未暴露：$APP_URL"
  fi

  echo "## PostgreSQL"
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${POSTGRES_PASSWORD:-}" psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
      "select relname, n_live_tup, seq_scan, idx_scan from pg_stat_user_tables order by n_live_tup desc limit 20;" 2>&1 || echo "psql 连接失败"
    PGPASSWORD="${POSTGRES_PASSWORD:-}" psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
      "select query, calls, mean_exec_time, total_exec_time from pg_stat_statements order by total_exec_time desc limit 10;" 2>&1 || \
      echo "pg_stat_statements 不可用（需在 postgresql.conf 开启 shared_preload_libraries）"
  else
    echo "未安装 psql，跳过"
  fi
  echo

  echo "## Redis"
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" INFO stats 2>&1 | grep -E "instantaneous_ops_per_sec|keyspace_hits|keyspace_misses|total_error_replies" || echo "Redis INFO 采集失败"
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" XLEN "interview:evaluate:stream" 2>&1 || true
  else
    echo "未安装 redis-cli，跳过"
  fi
  echo

  echo "## 宿主机资源"
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" 2>&1 | head -10
  else
    echo "Docker 守护进程未运行：跳过容器级 CPU/内存采集（记录为环境限制，不作为通过证据）"
  fi
} | tee "$OUT_FILE"

echo
echo "指标快照已写入: $OUT_FILE"
