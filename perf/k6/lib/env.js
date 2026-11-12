// 压测统一配置：所有脚本只读环境变量，不写死地址与令牌。
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const WS_BASE_URL = __ENV.WS_BASE_URL || 'ws://localhost:8080';
export const ACCESS_TOKEN = __ENV.ACCESS_TOKEN || '';
export const KNOWLEDGE_BASE_ID = __ENV.KNOWLEDGE_BASE_ID || '';
export const RESUME_ID = __ENV.RESUME_ID || '';
export const VUS = Number(__ENV.VUS || 10);
export const DURATION = __ENV.DURATION || '2m';

export function authHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  if (ACCESS_TOKEN) {
    headers['Authorization'] = `Bearer ${ACCESS_TOKEN}`;
  }
  return headers;
}
