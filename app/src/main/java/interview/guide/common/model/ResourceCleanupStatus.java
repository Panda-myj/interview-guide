package interview.guide.common.model;

/**
 * 资源清理补偿任务状态机。
 *
 * <pre>
 *   PENDING ──claim──> PROCESSING ──success──> COMPLETED
 *      ^                   │
 *      │                   └──failure──> FAILED ──(attempts >= max)──> DEAD
 *      └────────── retry scheduler ─────┘
 * </pre>
 *
 * FAILED 表示可重试的失败；DEAD 表示重试次数耗尽，需要人工介入。
 */
public enum ResourceCleanupStatus {
  PENDING,
  PROCESSING,
  FAILED,
  DEAD,
  COMPLETED
}
