/**
 * 前端路由唯一来源（FEDGE-004）。
 *
 * - `ROUTE_SEGMENTS` 供 `<Route path>` 使用：React Router 的嵌套路由需要相对 segment。
 * - `ROUTES` 由 segment 派生，供 `navigate` / `<Link to>` / 守卫重定向使用绝对路径。
 * - `path` 下的函数用于带参数路径，避免各页面手写模板字符串拼错。
 *
 * 新增路由时必须同时改这里与 `App.tsx` 的 `<Route>` 定义，两侧 key 一一对应。
 */
const SEGMENTS = {
  login: 'login',
  resumeHistory: 'history',
  resumeUpload: 'upload',
  resumeDetail: 'history/:resumeId',
  interviewHub: 'interview-hub',
  interviewHistory: 'interviews',
  interviewDetail: 'interviews/:sessionId',
  interview: 'interview',
  interviewWithResume: 'interview/:resumeId',
  voiceInterview: 'voice-interview',
  voiceEvaluation: 'voice-interview/:sessionId/evaluation',
  knowledgebase: 'knowledgebase',
  knowledgebaseUpload: 'knowledgebase/upload',
  knowledgebaseChat: 'knowledgebase/chat',
  interviewSchedule: 'interview-schedule',
  settings: 'settings',
  account: 'account',
} as const;

export type RouteKey = keyof typeof SEGMENTS;

/** 用于 `<Route path>`：相对 segment，不含前导斜杠。 */
export const ROUTE_SEGMENTS: Readonly<Record<RouteKey, string>> = SEGMENTS;

/** 用于导航：绝对路径，由 segment 派生，保证与 `<Route>` 定义不会漂移。 */
export const ROUTES = Object.fromEntries(
  (Object.keys(SEGMENTS) as RouteKey[]).map((key) => [key, `/${SEGMENTS[key]}`]),
) as Readonly<Record<RouteKey, string>>;

/** 带参数路径构造器。 */
export const path = {
  resumeDetail: (resumeId: number | string) => `/history/${resumeId}`,
  interviewDetail: (sessionId: string) => `/interviews/${sessionId}`,
  interviewWithResume: (resumeId: number | string) => `/interview/${resumeId}`,
  voiceEvaluation: (sessionId: string | number) => `/voice-interview/${sessionId}/evaluation`,
} as const;

/**
 * 侧边栏分组归属。
 *
 * 一个导航项可能对应多条路由（例如「简历管理」同时覆盖上传页和详情页），
 * 这类归属关系集中在这里，避免 Layout 里堆积 if 分支；新增子路由时只改一处。
 */
const NAV_GROUP_MATCHERS: Record<string, (currentPath: string) => boolean> = {
  [ROUTES.resumeHistory]: (currentPath) =>
    currentPath === ROUTES.resumeHistory
    || currentPath === '/'
    || currentPath.startsWith(`${ROUTES.resumeHistory}/`)
    || currentPath === ROUTES.resumeUpload,
  [ROUTES.interviewHub]: (currentPath) =>
    currentPath === ROUTES.interviewHub
    || currentPath === ROUTES.interview
    || currentPath.startsWith(`${ROUTES.interview}/`)
    || currentPath.startsWith(ROUTES.voiceInterview),
  [ROUTES.knowledgebase]: (currentPath) =>
    currentPath === ROUTES.knowledgebase || currentPath === ROUTES.knowledgebaseUpload,
};

/** 判断当前路径是否命中某个侧边栏导航项。 */
export function isNavItemActive(navPath: string, currentPath: string): boolean {
  if (navPath.startsWith('#')) {
    return false;
  }
  const matcher = NAV_GROUP_MATCHERS[navPath];
  return matcher ? matcher(currentPath) : currentPath.startsWith(navPath);
}
