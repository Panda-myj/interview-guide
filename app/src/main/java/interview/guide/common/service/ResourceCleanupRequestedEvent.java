package interview.guide.common.service;

/**
 * 业务事务提交后触发的资源清理事件，保证“先删库、后清资源”的顺序。
 */
public record ResourceCleanupRequestedEvent(Long compensationTaskId) {
}
