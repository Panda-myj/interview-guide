import request from './request';
import type {
  AsrConfig,
  AsrConfigRequest,
  ProviderTestResult,
  TtsConfig,
  TtsConfigRequest,
  UserDefaultRequest,
  UserProviderItem,
  UserSaveProviderRequest,
} from '../types/llmProvider';

// 统一的用户级模型配置入口：多张卡片（文本 + 向量）与语音服务，
// 所有登录用户（含管理员）使用同一套接口，只能访问自己的配置。
export const userLlmProviderApi = {
  list: () => request.get<UserProviderItem[]>('/api/user-llm-provider/list'),

  create: (data: UserSaveProviderRequest) =>
    request.post<UserProviderItem>('/api/user-llm-provider', data),

  update: (id: number, data: UserSaveProviderRequest) =>
    request.put<void>(`/api/user-llm-provider/${id}`, data),

  delete: (id: number) =>
    request.delete<void>(`/api/user-llm-provider/${id}`),

  test: (id: number) =>
    request.post<ProviderTestResult>(`/api/user-llm-provider/${id}/test`),

  setDefaultChatProvider: (data: UserDefaultRequest) =>
    request.put<void>('/api/user-llm-provider/default-provider', data),

  setDefaultEmbeddingProvider: (data: UserDefaultRequest) =>
    request.put<void>('/api/user-llm-provider/default-embedding-provider', data),

  // ===== 语音服务 =====
  getAsrConfig: () =>
    request.get<AsrConfig>('/api/user-llm-provider/voice/asr'),

  saveAsrConfig: (data: AsrConfigRequest) =>
    request.put<void>('/api/user-llm-provider/voice/asr', data),

  getTtsConfig: () =>
    request.get<TtsConfig>('/api/user-llm-provider/voice/tts'),

  saveTtsConfig: (data: TtsConfigRequest) =>
    request.put<void>('/api/user-llm-provider/voice/tts', data),

  testAsr: () =>
    request.post<ProviderTestResult>('/api/user-llm-provider/voice/asr/test'),
};
