# 前端学习清单

> 目标：掌握本项目前端（React 18 + TypeScript + Vite + Tailwind CSS 4），能在 AI 辅助下独立全栈开发。
> 原则：**按项目实际需要学，能看懂 + 会改 + 会调 API 即可，不追求精通。**

---

## 一、本项目前端技术栈（只需掌握前 4 项）

| 技术 | 版本 | 用途 | 掌握程度 |
| ---- | ---- | ---- | -------- |
| TypeScript | 5.6 | 开发语言 | 会读类型、写 interface/type |
| React | 18.3 | UI 框架 | **重点** |
| Vite | 5.4 | 构建工具 | 会用 `pnpm dev/build` 即可 |
| Tailwind CSS | 4.1 | 样式框架 | 会写工具类 |
| React Router | 7.11 | 路由 | 懂路由配置 |
| axios | 1.7 | HTTP 请求 | **重点**（看 `api/request.ts`） |
| 其它（了解即可） | - | framer-motion 动画、recharts 图表、react-big-calendar 日历、react-virtuoso 虚拟列表、react-markdown 渲染 | 用到再查 |

## 二、项目前端目录地图（对照学最快）

```
frontend/
├── src/
│   ├── api/            # API 封装（已经封装好的 request/stream，直接调用）
│   │   ├── request.ts  # axios 统一封装（Result 解包、错误处理、上传/下载）
│   │   ├── stream.ts   # SSE 流式响应解析（知识库问答打字机效果用）
│   │   └── *.ts        # 每个业务模块一个文件（interview/resume/ragChat/voiceInterview...）
│   ├── components/     # 可复用组件
│   ├── hooks/          # 业务 Hooks（复用逻辑）
│   ├── pages/          # 页面（13 个，对应每个功能页面）
│   ├── types/          # 共享 TypeScript 类型
│   ├── constants/routes.ts  # 路由常量
│   └── utils/          # 工具函数
├── vite.config.ts      # Vite 配置
└── package.json
```

**典型调用链**：页面 `pages/` → 调 `api/*.ts` 的方法 → 底层走 `request.ts`（axios）→ 后端。

## 三、必学核心知识（约 3 天）

### 1. TypeScript 最小集
- `interface` / `type` 定义数据结构（对照 `src/types/` 看即可）
- 泛型基础：`Promise<T>`、`useState<T>`（`useState<number>` 这种）
- `unknown` 与类型断言（项目里 `as` 用得很多）

### 2. React 最小集（Hooks 是重中之重）
| Hook | 场景 | 项目中的应用 |
| ---- | ---- | ---- |
| `useState` | 存页面状态 | 表单、聊天消息列表 |
| `useEffect` | 挂载时拉数据 / 副作用 | 页面加载调 API |
| `useRef` | 存不触发渲染的变量、DOM 引用 | 音频/滚动容器 |
| `useMemo`/`useCallback` | 性能优化 | 大列表、频繁重渲染处 |
| 自定义 Hook | 复用逻辑 | `src/hooks/` 里的业务逻辑 |

补充必懂概念：props 传参、条件渲染（`&&` / 三元）、列表渲染（`map` + `key`）、受控表单、`async/await`。

### 3. axios 统一封装（看 `src/api/request.ts`）
- 后端约定：**所有响应都是 HTTP 200 + `Result<T>`**（`code===200` 成功返回 `data`，否则 throw message）
- 已封装好：`request.get/post/put/delete/upload/download`，直接调用即可
- 错误已统一处理，页面只需 `try/catch` + `getErrorMessage`

```ts
// 页面里典型用法
const data = await request.post<XxxResponse>('/api/xxx', payload);
```

### 4. SSE 流式（看 `src/api/stream.ts`）
- 知识库问答 / RAG 用 `streamSse()` 实现打字机效果
- 提供 `onMessage/onComplete/onError` 回调，只读用法即可

### 5. Tailwind CSS
- 项目已预配置，直接写类名：`flex items-center gap-2 px-4` 等
- 只需会用：布局（flex/grid）、间距（m/p）、颜色（text-/bg-）、状态（hover:）
- 参照已有页面的写法模仿即可

### 6. 路由
- `react-router-dom`，在 `constants/routes.ts` 定义路由，页面文件对应即可

## 四、开发工作流（AI 辅助全栈开发关键）

```
1. 后端写接口 → Swagger 看返回结构 → 在 src/types/ 定义类型
2. 在 src/api/ 对应模块文件加一个方法（复制相邻方法改 URL/类型）
3. 对已有组件/pages，复制最相似的页面改
4. pnpm dev 本地跑，浏览器 F12 看 Network/报错调整
```

## 五、常用命令

```bash
cd frontend
corepack enable          # 启用 pnpm
pnpm install
pnpm dev                 # 本地开发 http://localhost:5173
pnpm run build           # 构建验证（改完代码至少跑一次，含 tsc 类型检查）
```

## 六、学习资源（按顺序）

1. **React 官方文档** → https://react.dev/learn （只学 Learn 部分，2-3 天）
2. **项目代码实战** → 通读一个完整页面（如 `KnowledgeBaseQueryPage.tsx` 或 `InterviewPage.tsx`），对照 `api/ragChat.ts` / `api/interview.ts` 理解「页面 → API → 后端」全链路
3. **TypeScript** → https://www.typescriptlang.org/docs/handbook/typescript-from-scratch.html （只看类型基础）
4. **Tailwind** → https://tailwindcss.com/docs （用到查，不用系统学）

## 七、常见坑

- 后端返回的是 `Result<T>`，**不是裸数据**——不用管，`request` 已解包；但看 Swagger 时要习惯。
- 文件上传用 `request.upload`，下载用 `request.download`（走 Blob，超时更长）。
- 流的请求走 `streamSse`，别用普通 `request.get`。
- 改代码后跑 `pnpm run build`，有类型错误会直接报红。