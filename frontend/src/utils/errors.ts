import type { Result } from '../api/request';

/**
 * 把任意异常/响应归一为用户可读文案。
 *
 * 规则（FEDGE-003）：
 * 1. 后端 `Result.error(code, message)` 的 message 已面向用户，直接使用；
 * 2. Error 实例使用其 message；
 * 3. 网络中断、超时给出明确原因，而不是 "undefined"；
 * 4. 其他情况回退到调用方提供的兜底文案。
 */
export function toErrorMessage(error: unknown, fallback = '操作失败，请稍后重试'): string {
  if (!error) {
    return fallback;
  }
  if (typeof error === 'string') {
    return error || fallback;
  }
  if (error instanceof Error) {
    const message = error.message?.trim();
    if (!message) {
      return fallback;
    }
    if (message.includes('timeout')) {
      return '请求超时，请检查网络后重试';
    }
    if (message.includes('Network Error')) {
      return '网络连接失败，请检查网络后重试';
    }
    return message;
  }
  const result = error as Partial<Result>;
  if (typeof result.message === 'string' && result.message.trim()) {
    return result.message;
  }
  return fallback;
}

/**
 * 判断是否为权限类错误，便于 UI 决定是否展示「无权限」空状态而非普通错误。
 */
export function isPermissionError(error: unknown): boolean {
  const result = error as Partial<Result>;
  if (typeof result?.code === 'number') {
    // 401 未认证 / 403 无权限
    return result.code === 401 || result.code === 403;
  }
  return false;
}
