import { useState, useEffect, useCallback } from 'react';
import type { ReactNode } from 'react';
import { motion } from 'framer-motion';
import {
  Settings, Plus, Trash2, CheckCircle, XCircle,
  Loader2, Eye, EyeOff, Server, Edit2, Mic, Volume2,
} from 'lucide-react';
import { userLlmProviderApi } from '../api/userLlmProvider';
import ConfirmDialog from '../components/ConfirmDialog';
import type {
  UserProviderItem, UserSaveProviderRequest, ProviderTestResult,
  AsrConfig, TtsConfig, AsrConfigRequest, TtsConfigRequest,
} from '../types/llmProvider';

// Provider 预设：已知 Provider 的 Base URL、推荐模型和向量模型
const PROVIDER_PRESETS: Record<string, {
  baseUrl: string;
  models: { value: string; label: string }[];
  embeddingModels?: { value: string; label: string }[];
  embeddingDimensions?: number;
  supportsEmbedding: boolean;
}> = {
  dashscope: {
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: [
      { value: 'qwen3.6-flash', label: 'Qwen3.6 Flash — 最新旗舰' },
      { value: 'qwen3.5-plus', label: 'Qwen3.5 Plus — 高性能' },
      { value: 'qwen3.5-flash', label: 'Qwen3.5 Flash — 性价比' },
      { value: 'qwen3-max', label: 'Qwen3 Max — 旗舰' },
      { value: 'qwen-max', label: 'Qwen Max — 稳定版' },
      { value: 'qwen-plus', label: 'Qwen Plus — 均衡' },
      { value: 'qwen-flash', label: 'Qwen Flash — 经济' },
      { value: 'qwq-32b', label: 'QwQ-32B — 推理专用' },
    ],
    embeddingModels: [
      { value: 'text-embedding-v3', label: 'text-embedding-v3 — 推荐' },
    ],
    embeddingDimensions: 1024,
    supportsEmbedding: true,
  },
  deepseek: {
    baseUrl: 'https://api.deepseek.com',
    models: [
      { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash — 最新·快速' },
      { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro — 最强推理' },
      { value: 'deepseek-chat', label: 'DeepSeek V3.2 — 旧版对话（即将弃用）' },
      { value: 'deepseek-reasoner', label: 'DeepSeek R1 — 旧版推理（即将弃用）' },
    ],
    supportsEmbedding: false,
  },
  glm: {
    baseUrl: 'https://open.bigmodel.cn/api/coding/paas/v4',
    models: [
      { value: 'glm-5.1', label: 'GLM-5.1 — 最新旗舰' },
      { value: 'glm-5', label: 'GLM-5 — 旗舰' },
      { value: 'glm-4.7', label: 'GLM-4.7 — Coding 强' },
      { value: 'glm-4.7-flash', label: 'GLM-4.7 Flash — 免费' },
      { value: 'glm-4.6', label: 'GLM-4.6 — 200K 上下文' },
      { value: 'glm-4-plus', label: 'GLM-4 Plus — 高性能' },
      { value: 'glm-4-air-250414', label: 'GLM-4 Air — 高性价比' },
      { value: 'glm-4-flash-250414', label: 'GLM-4 Flash — 免费' },
    ],
    embeddingModels: [
      { value: 'embedding-3', label: 'embedding-3 — 推荐' },
    ],
    embeddingDimensions: 1024,
    supportsEmbedding: true,
  },
  kimi: {
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      { value: 'kimi-k2.6', label: 'Kimi K2.6 — 最新最智能' },
      { value: 'kimi-k2.5', label: 'Kimi K2.5 — 多模态' },
      { value: 'kimi-k2', label: 'Kimi K2 — MoE 基座' },
      { value: 'kimi-k2-thinking', label: 'Kimi K2 Thinking — 深度推理' },
      { value: 'kimi-latest', label: 'kimi-latest — 自动最新' },
    ],
    supportsEmbedding: false,
  },
};

type ConfigRowProps = {
  label: string;
  value: ReactNode;
  title?: string;
  monospace?: boolean;
  emphasis?: boolean;
};

function ConfigRow({ label, value, title, monospace, emphasis }: ConfigRowProps) {
  return (
    <div className="flex items-start gap-2">
      <dt className="w-24 flex-shrink-0 pt-0.5 text-xs text-slate-500 dark:text-slate-400">{label}</dt>
      <dd
        title={title}
        className={`flex-1 break-all text-xs text-slate-800 dark:text-slate-200 ${
          monospace ? 'font-mono' : ''
        } ${emphasis ? 'font-semibold' : ''}`}
      >
        {value}
      </dd>
    </div>
  );
}

const ACTION_BUTTON_CLASS = `inline-flex h-8 items-center gap-1.5 rounded-lg px-3 text-xs
  font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50`;

const INPUT_CLASS = `w-full px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-600
  bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
  placeholder:text-slate-400 focus:outline-none focus:ring-2
  focus:ring-primary-500/50 focus:border-primary-400 transition-shadow`;

const LABEL_CLASS = 'block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1';

type CardFormState = {
  name: string;
  presetKey: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  supportsEmbedding: boolean;
  embeddingModel: string;
  embeddingDimensions: string;
  temperature: string;
};

const EMPTY_CARD_FORM: CardFormState = {
  name: '',
  presetKey: '',
  baseUrl: '',
  apiKey: '',
  model: '',
  supportsEmbedding: false,
  embeddingModel: '',
  embeddingDimensions: '',
  temperature: '',
};

export default function SettingsPage() {
  const [cards, setCards] = useState<UserProviderItem[]>([]);
  const [loading, setLoading] = useState(true);

  // 卡片表单（新增 / 编辑共用）
  const [cardFormOpen, setCardFormOpen] = useState(false);
  const [editingCard, setEditingCard] = useState<UserProviderItem | null>(null);
  const [form, setForm] = useState<CardFormState>(EMPTY_CARD_FORM);
  const [showApiKey, setShowApiKey] = useState(false);
  const [formSaving, setFormSaving] = useState(false);

  // 卡片操作状态
  const [testingId, setTestingId] = useState<number | null>(null);
  const [cardTestResults, setCardTestResults] = useState<Record<number, ProviderTestResult | undefined>>({});
  const [deleteConfirmId, setDeleteConfirmId] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);

  // 语音配置
  const [asrConfig, setAsrConfig] = useState<AsrConfig | null>(null);
  const [ttsConfig, setTtsConfig] = useState<TtsConfig | null>(null);
  const [asrForm, setAsrForm] = useState<AsrConfigRequest>({});
  const [ttsForm, setTtsForm] = useState<TtsConfigRequest>({});
  const [asrSaving, setAsrSaving] = useState(false);
  const [ttsSaving, setTtsSaving] = useState(false);
  const [asrTesting, setAsrTesting] = useState(false);
  const [asrTestResult, setAsrTestResult] = useState<ProviderTestResult | null>(null);

  // Toast notification
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  }, []);

  const applyVoiceConfig = useCallback((asr: AsrConfig, tts: TtsConfig) => {
    setAsrConfig(asr);
    setTtsConfig(tts);
    setAsrForm({
      url: asr.url ?? '',
      model: asr.model ?? '',
      apiKey: '',
      language: asr.language ?? '',
      format: asr.format ?? '',
      sampleRate: asr.sampleRate,
      enableTurnDetection: asr.enableTurnDetection,
      turnDetectionType: asr.turnDetectionType ?? '',
      turnDetectionThreshold: asr.turnDetectionThreshold,
      turnDetectionSilenceDurationMs: asr.turnDetectionSilenceDurationMs,
    });
    setTtsForm({
      model: tts.model ?? '',
      apiKey: '',
      voice: tts.voice ?? '',
      format: tts.format ?? '',
      sampleRate: tts.sampleRate,
      mode: tts.mode ?? '',
      languageType: tts.languageType ?? '',
      speechRate: tts.speechRate,
      volume: tts.volume,
    });
  }, []);

  const loadData = useCallback(async () => {
    try {
      const [cardList, asr, tts] = await Promise.all([
        userLlmProviderApi.list(),
        userLlmProviderApi.getAsrConfig(),
        userLlmProviderApi.getTtsConfig(),
      ]);
      setCards(cardList);
      applyVoiceConfig(asr, tts);
    } catch (err) {
      console.error('Failed to load settings:', err);
      showToast('加载数据失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast, applyVoiceConfig]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // ===== 卡片表单 =====

  const applyPreset = (presetKey: string) => {
    const preset = PROVIDER_PRESETS[presetKey];
    setForm((prev) => ({
      ...prev,
      presetKey,
      baseUrl: preset ? preset.baseUrl : prev.baseUrl,
      model: preset && preset.models.length > 0 ? preset.models[0].value : prev.model,
      supportsEmbedding: preset ? preset.supportsEmbedding : false,
      embeddingModel: preset?.embeddingModels && preset.embeddingModels.length > 0
        ? preset.embeddingModels[0].value : '',
      embeddingDimensions: preset?.embeddingDimensions ? String(preset.embeddingDimensions) : '',
    }));
  };

  const openCreateCard = () => {
    setEditingCard(null);
    setForm(EMPTY_CARD_FORM);
    setShowApiKey(false);
    setCardFormOpen(true);
  };

  const openEditCard = (card: UserProviderItem) => {
    setEditingCard(card);
    setForm({
      name: card.name,
      presetKey: '',
      baseUrl: card.baseUrl,
      apiKey: '',
      model: card.model,
      supportsEmbedding: card.supportsEmbedding,
      embeddingModel: card.embeddingModel ?? '',
      embeddingDimensions: card.embeddingDimensions != null ? String(card.embeddingDimensions) : '',
      temperature: card.temperature != null ? String(card.temperature) : '',
    });
    setShowApiKey(false);
    setCardFormOpen(true);
  };

  const handleSaveCard = async () => {
    if (!form.name.trim() || !form.baseUrl.trim() || !form.model.trim()) {
      showToast('请填写卡片名称、Base URL 和聊天模型', 'error');
      return;
    }
    if (!editingCard && !form.apiKey.trim()) {
      showToast('创建卡片请填写 API Key', 'error');
      return;
    }
    if (form.supportsEmbedding && !form.embeddingModel.trim()) {
      showToast('启用向量模型时请填写向量模型名称', 'error');
      return;
    }
    setFormSaving(true);
    try {
      const data: UserSaveProviderRequest = {
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        model: form.model.trim(),
        supportsEmbedding: form.supportsEmbedding,
      };
      if (form.apiKey.trim()) {
        data.apiKey = form.apiKey.trim();
      }
      if (form.supportsEmbedding && form.embeddingModel.trim()) {
        data.embeddingModel = form.embeddingModel.trim();
        const dims = parseInt(form.embeddingDimensions, 10);
        if (!isNaN(dims) && dims > 0) {
          data.embeddingDimensions = dims;
        }
      }
      if (form.temperature.trim()) {
        const temp = parseFloat(form.temperature.trim());
        if (!isNaN(temp)) data.temperature = temp;
      }
      if (editingCard) {
        await userLlmProviderApi.update(editingCard.id, data);
        showToast('卡片已更新');
      } else {
        const created = await userLlmProviderApi.create(data);
        showToast(cards.length === 0
          ? '卡片已创建，并已自动设为默认文本模型'
          : '卡片已创建');
        if (created) setCards((prev) => [...prev, created]);
      }
      setCardFormOpen(false);
      await loadData();
    } catch (err) {
      console.error('Failed to save card:', err);
      showToast(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setFormSaving(false);
    }
  };

  // ===== 卡片操作 =====

  const handleTestCard = async (cardId: number) => {
    setTestingId(cardId);
    setCardTestResults((prev) => ({ ...prev, [cardId]: undefined }));
    try {
      const result = await userLlmProviderApi.test(cardId);
      setCardTestResults((prev) => ({ ...prev, [cardId]: result }));
    } catch (err) {
      console.error('Test failed:', err);
      setCardTestResults((prev) => ({
        ...prev,
        [cardId]: {
          success: false,
          message: err instanceof Error ? err.message : '连接测试失败',
          model: '',
        },
      }));
    } finally {
      setTestingId(null);
    }
  };

  const handleSetDefaultChat = async (cardId: number) => {
    try {
      await userLlmProviderApi.setDefaultChatProvider({ providerId: cardId });
      showToast('已设为默认文本模型');
      await loadData();
    } catch (err) {
      console.error('Failed to set default:', err);
      showToast(err instanceof Error ? err.message : '设置失败', 'error');
    }
  };

  const handleSetDefaultEmbedding = async (cardId: number) => {
    try {
      await userLlmProviderApi.setDefaultEmbeddingProvider({ providerId: cardId });
      showToast('已设为默认向量模型');
      await loadData();
    } catch (err) {
      console.error('Failed to set default embedding:', err);
      showToast(err instanceof Error ? err.message : '设置失败', 'error');
    }
  };

  const handleDeleteCard = async () => {
    if (deleteConfirmId == null) return;
    setDeleting(true);
    try {
      await userLlmProviderApi.delete(deleteConfirmId);
      showToast('卡片已删除');
      setDeleteConfirmId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to delete card:', err);
      showToast(err instanceof Error ? err.message : '删除失败', 'error');
    } finally {
      setDeleting(false);
    }
  };

  // ===== 语音配置 =====

  const handleSaveAsr = async () => {
    setAsrSaving(true);
    try {
      await userLlmProviderApi.saveAsrConfig(asrForm);
      showToast('语音识别配置已保存');
      await loadData();
    } catch (err) {
      console.error('Failed to save ASR config:', err);
      showToast(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setAsrSaving(false);
    }
  };

  const handleSaveTts = async () => {
    setTtsSaving(true);
    try {
      await userLlmProviderApi.saveTtsConfig(ttsForm);
      showToast('语音合成配置已保存');
      await loadData();
    } catch (err) {
      console.error('Failed to save TTS config:', err);
      showToast(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setTtsSaving(false);
    }
  };

  const handleTestAsr = async () => {
    setAsrTesting(true);
    setAsrTestResult(null);
    try {
      const result = await userLlmProviderApi.testAsr();
      setAsrTestResult(result);
    } catch (err) {
      console.error('ASR test failed:', err);
      setAsrTestResult({
        success: false,
        message: err instanceof Error ? err.message : '测试失败',
        model: '',
      });
    } finally {
      setAsrTesting(false);
    }
  };

  const defaultChatCard = cards.find((card) => card.defaultChatProvider);
  const defaultEmbeddingCard = cards.find((card) => card.defaultEmbeddingProvider);

  const presetKeys = Object.keys(PROVIDER_PRESETS);
  const activePreset = form.presetKey ? PROVIDER_PRESETS[form.presetKey] : undefined;
  const chatModelOptions = activePreset?.models;
  const embeddingModelOptions = activePreset?.embeddingModels;

  // --- Render ---
  return (
    <div className="min-h-screen">
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        {/* Header */}
        <div className="mb-6">
          <h1 className="flex items-center gap-2 text-2xl font-bold text-slate-800 dark:text-white">
            <Settings className="h-6 w-6" />
            设置
          </h1>
          <p className="mt-0.5 text-sm text-slate-500 dark:text-slate-400">
            配置我自己的模型卡片与语音服务，AI 调用费用由本人承担
          </p>
        </div>

        {loading ? (
          <div className="flex min-h-[50vh] items-center justify-center">
            <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
          </div>
        ) : (
          <>
            {/* 未配置默认文本模型提示 */}
            {!defaultChatCard && (
              <div className="mb-4 flex items-start gap-3 rounded-xl border border-amber-200
                bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-500/30
                dark:bg-amber-900/20 dark:text-amber-200">
                <XCircle className="mt-0.5 h-4 w-4 flex-shrink-0" />
                <span>
                  您尚未设置默认文本模型：模拟面试、简历分析、知识库问答、JD 解析等 AI 功能暂时无法使用。
                  请在下方添加模型卡片并设为默认文本模型。
                </span>
              </div>
            )}

            {/* ===== 模型卡片 ===== */}
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.15 }}
            >
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white">
                  模型卡片
                </h2>
                <button
                  onClick={openCreateCard}
                  className="inline-flex h-9 items-center gap-1.5 rounded-lg px-4 text-sm font-semibold
                    bg-gradient-to-r from-primary-500 to-primary-600 text-white
                    shadow-md shadow-primary-500/25 hover:from-primary-600 hover:to-primary-700
                    transition-all"
                >
                  <Plus className="h-4 w-4" />
                  添加模型卡片
                </button>
              </div>

              {cards.length === 0 ? (
                <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center
                  text-sm text-slate-500 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-400">
                  还没有模型卡片。点击「添加模型卡片」，填入您自己的 Base URL、API Key 和模型即可开始使用。
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
                  {cards.map((card) => {
                    const testResult = cardTestResults[card.id];
                    return (
                      <div
                        key={card.id}
                        className={`flex flex-col rounded-xl border bg-white p-5 shadow-sm transition-shadow hover:shadow-md
                          dark:bg-slate-800 ${
                            card.defaultChatProvider
                              ? 'border-primary-300 dark:border-primary-500/50'
                              : 'border-slate-200 dark:border-slate-700'
                          }`}
                      >
                        <div className="mb-3 flex items-start justify-between">
                          <div className="flex min-w-0 items-center gap-2.5">
                            <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg
                              bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
                              <Server className="h-4 w-4" />
                            </div>
                            <div className="min-w-0">
                              <p className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                                {card.name}
                              </p>
                              <div className="mt-1 flex flex-wrap gap-1">
                                {card.defaultChatProvider && (
                                  <span className="inline-flex h-5 items-center rounded-full bg-primary-50 px-2
                                    text-[10px] font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                                    默认文本模型
                                  </span>
                                )}
                                {card.defaultEmbeddingProvider && (
                                  <span className="inline-flex h-5 items-center rounded-full bg-emerald-50 px-2
                                    text-[10px] font-semibold text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300">
                                    默认向量模型
                                  </span>
                                )}
                              </div>
                            </div>
                          </div>
                        </div>

                        <dl className="mb-4 flex-1 space-y-1 rounded-lg border border-slate-100 bg-slate-50/70
                          p-3 dark:border-slate-700/80 dark:bg-slate-900/30">
                          <ConfigRow label="聊天模型" value={card.model} emphasis />
                          <ConfigRow label="Base URL" value={card.baseUrl} title={card.baseUrl} monospace />
                          <ConfigRow
                            label="API Key"
                            value={card.maskedApiKey}
                            title={card.maskedApiKey}
                            monospace
                          />
                          <ConfigRow
                            label="向量模型"
                            value={card.supportsEmbedding && card.embeddingModel
                              ? `${card.embeddingModel}${card.embeddingDimensions ? ` (${card.embeddingDimensions}维)` : ''}`
                              : '未配置'}
                          />
                          {card.temperature != null && (
                            <ConfigRow label="Temperature" value={String(card.temperature)} />
                          )}
                        </dl>

                        {testResult && (
                          <div className={`mb-3 rounded-lg px-3 py-2 text-xs font-medium ${
                            testResult.success
                              ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300'
                              : 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
                          }`}>
                            <div className="flex items-start gap-1.5">
                              {testResult.success
                                ? <CheckCircle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
                                : <XCircle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0" />
                              }
                              <span>{testResult.message}</span>
                            </div>
                          </div>
                        )}

                        <div className="mt-auto flex flex-wrap items-center gap-2 border-t
                          border-slate-100 pt-3 dark:border-slate-700">
                          <button
                            onClick={() => handleTestCard(card.id)}
                            disabled={testingId === card.id}
                            className={`${ACTION_BUTTON_CLASS} text-blue-600 hover:bg-blue-50
                              dark:text-blue-400 dark:hover:bg-blue-900/20`}
                          >
                            {testingId === card.id
                              ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                              : <CheckCircle className="h-3.5 w-3.5" />
                            }
                            测试连接
                          </button>
                          {!card.defaultChatProvider && (
                            <button
                              onClick={() => handleSetDefaultChat(card.id)}
                              className={`${ACTION_BUTTON_CLASS} text-primary-600 hover:bg-primary-50
                                dark:text-primary-400 dark:hover:bg-primary-900/20`}
                            >
                              设为默认文本模型
                            </button>
                          )}
                          {card.supportsEmbedding && card.embeddingModel && !card.defaultEmbeddingProvider && (
                            <button
                              onClick={() => handleSetDefaultEmbedding(card.id)}
                              className={`${ACTION_BUTTON_CLASS} text-emerald-600 hover:bg-emerald-50
                                dark:text-emerald-400 dark:hover:bg-emerald-900/20`}
                            >
                              设为默认向量模型
                            </button>
                          )}
                          <button
                            onClick={() => openEditCard(card)}
                            className={`${ACTION_BUTTON_CLASS} text-slate-500 hover:bg-slate-100
                              dark:text-slate-400 dark:hover:bg-slate-700`}
                          >
                            <Edit2 className="h-3.5 w-3.5" />
                            编辑
                          </button>
                          <button
                            onClick={() => setDeleteConfirmId(card.id)}
                            className={`${ACTION_BUTTON_CLASS} text-slate-400 hover:bg-red-50 hover:text-red-500
                              dark:hover:bg-red-900/20 dark:hover:text-red-300`}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                            删除
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}

              {!defaultEmbeddingCard && cards.length > 0 && (
                <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">
                  提示：知识库向量化与检索需要默认向量模型。可在支持向量模型的卡片上填写向量模型后设为默认。
                </p>
              )}
            </motion.div>

            {/* ===== 语音服务 ===== */}
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.15 }}
              className="mt-8"
            >
              <div className="mb-4 flex items-center justify-between">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white">
                  语音服务
                </h2>
              </div>
              <p className="mb-4 text-xs text-slate-500 dark:text-slate-400">
                语音面试使用的识别与合成服务。未配置时使用系统默认；配置后语音面试将使用您自己的服务与密钥。
              </p>

              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                {/* ASR */}
                <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
                  <div className="mb-4 flex items-center gap-2.5">
                    <div className="flex h-9 w-9 items-center justify-center rounded-lg
                      bg-sky-50 text-sky-600 dark:bg-sky-900/30 dark:text-sky-300">
                      <Mic className="h-4 w-4" />
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-slate-800 dark:text-white">语音识别（ASR）</p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        {asrConfig?.model ? `当前：${asrConfig.model}` : '未配置，使用系统默认'}
                      </p>
                    </div>
                  </div>

                  {asrTestResult && (
                    <div className={`mb-3 rounded-lg px-3 py-2 text-xs font-medium ${
                      asrTestResult.success
                        ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300'
                        : 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
                    }`}>
                      {asrTestResult.message}
                    </div>
                  )}

                  <div className="grid grid-cols-1 gap-3">
                    <div>
                      <label className={LABEL_CLASS}>WebSocket 地址</label>
                      <input
                        type="text"
                        value={asrForm.url ?? ''}
                        onChange={(e) => setAsrForm((prev) => ({ ...prev, url: e.target.value }))}
                        placeholder="例如: wss://dashscope.aliyuncs.com/api-ws/v1/inference"
                        className={INPUT_CLASS}
                      />
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className={LABEL_CLASS}>模型</label>
                        <input
                          type="text"
                          value={asrForm.model ?? ''}
                          onChange={(e) => setAsrForm((prev) => ({ ...prev, model: e.target.value }))}
                          placeholder="qwen3-asr-flash-realtime"
                          className={INPUT_CLASS}
                        />
                      </div>
                      <div>
                        <label className={LABEL_CLASS}>API Key</label>
                        <input
                          type="password"
                          value={asrForm.apiKey ?? ''}
                          onChange={(e) => setAsrForm((prev) => ({ ...prev, apiKey: e.target.value }))}
                          placeholder={asrConfig?.maskedApiKey
                            ? `已保存 ${asrConfig.maskedApiKey}，留空不修改`
                            : '输入您的 API Key'}
                          className={INPUT_CLASS}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="mt-4 flex items-center gap-2">
                    <button
                      onClick={handleSaveAsr}
                      disabled={asrSaving}
                      className="inline-flex h-9 items-center gap-2 rounded-lg px-4 text-sm font-semibold
                        bg-gradient-to-r from-primary-500 to-primary-600 text-white
                        shadow-md shadow-primary-500/25 hover:from-primary-600 hover:to-primary-700
                        transition-all disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {asrSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle className="h-4 w-4" />}
                      保存
                    </button>
                    <button
                      onClick={handleTestAsr}
                      disabled={asrTesting}
                      className={`${ACTION_BUTTON_CLASS} text-blue-600 hover:bg-blue-50
                        dark:text-blue-400 dark:hover:bg-blue-900/20`}
                    >
                      {asrTesting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle className="h-3.5 w-3.5" />}
                      测试连接
                    </button>
                  </div>
                </div>

                {/* TTS */}
                <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
                  <div className="mb-4 flex items-center gap-2.5">
                    <div className="flex h-9 w-9 items-center justify-center rounded-lg
                      bg-violet-50 text-violet-600 dark:bg-violet-900/30 dark:text-violet-300">
                      <Volume2 className="h-4 w-4" />
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-slate-800 dark:text-white">语音合成（TTS）</p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        {ttsConfig?.model ? `当前：${ttsConfig.model}` : '未配置，使用系统默认'}
                      </p>
                    </div>
                  </div>

                  <div className="grid grid-cols-1 gap-3">
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className={LABEL_CLASS}>模型</label>
                        <input
                          type="text"
                          value={ttsForm.model ?? ''}
                          onChange={(e) => setTtsForm((prev) => ({ ...prev, model: e.target.value }))}
                          placeholder="qwen-audio-3.1-tts-flash"
                          className={INPUT_CLASS}
                        />
                      </div>
                      <div>
                        <label className={LABEL_CLASS}>音色</label>
                        <input
                          type="text"
                          value={ttsForm.voice ?? ''}
                          onChange={(e) => setTtsForm((prev) => ({ ...prev, voice: e.target.value }))}
                          placeholder="Cherry"
                          className={INPUT_CLASS}
                        />
                      </div>
                    </div>
                    <div>
                      <label className={LABEL_CLASS}>API Key</label>
                      <input
                        type="password"
                        value={ttsForm.apiKey ?? ''}
                        onChange={(e) => setTtsForm((prev) => ({ ...prev, apiKey: e.target.value }))}
                        placeholder={ttsConfig?.maskedApiKey
                          ? `已保存 ${ttsConfig.maskedApiKey}，留空不修改`
                          : '输入您的 API Key'}
                        className={INPUT_CLASS}
                      />
                    </div>
                  </div>

                  <div className="mt-4">
                    <button
                      onClick={handleSaveTts}
                      disabled={ttsSaving}
                      className="inline-flex h-9 items-center gap-2 rounded-lg px-4 text-sm font-semibold
                        bg-gradient-to-r from-primary-500 to-primary-600 text-white
                        shadow-md shadow-primary-500/25 hover:from-primary-600 hover:to-primary-700
                        transition-all disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {ttsSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle className="h-4 w-4" />}
                      保存
                    </button>
                  </div>
                </div>
              </div>
            </motion.div>
          </>
        )}
      </div>

      {/* 卡片表单弹窗 */}
      {cardFormOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div
            className="absolute inset-0 bg-slate-900/50 backdrop-blur-sm"
            onClick={() => !formSaving && setCardFormOpen(false)}
          />
          <motion.div
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            className="relative max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-2xl border
              border-slate-200 bg-white p-6 shadow-xl dark:border-slate-700 dark:bg-slate-800"
          >
            <h3 className="mb-1 text-lg font-bold text-slate-800 dark:text-white">
              {editingCard ? '编辑模型卡片' : '添加模型卡片'}
            </h3>
            <p className="mb-4 text-xs text-slate-500 dark:text-slate-400">
              选择预设可自动填充地址与推荐模型；也可以手动填写任意 OpenAI 兼容接口。
            </p>

            <div className="grid grid-cols-1 gap-3">
              <div>
                <label className={LABEL_CLASS}>预设</label>
                <select
                  value={form.presetKey}
                  onChange={(e) => applyPreset(e.target.value)}
                  className={INPUT_CLASS}
                >
                  <option value="">自定义 / 手动填写</option>
                  {presetKeys.map((key) => (
                    <option key={key} value={key}>{key}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className={LABEL_CLASS}>卡片名称 <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm((prev) => ({ ...prev, name: e.target.value }))}
                  placeholder="例如: 我的通义千问"
                  className={INPUT_CLASS}
                />
              </div>
              <div>
                <label className={LABEL_CLASS}>Base URL <span className="text-red-500">*</span></label>
                <input
                  type="text"
                  value={form.baseUrl}
                  onChange={(e) => setForm((prev) => ({ ...prev, baseUrl: e.target.value }))}
                  placeholder="https://...compatible-mode/v1"
                  className={INPUT_CLASS}
                />
              </div>
              <div>
                <label className={LABEL_CLASS}>
                  API Key{editingCard && <span className="font-normal text-slate-400">（留空则保持原值）</span>}
                  {!editingCard && <span className="text-red-500"> *</span>}
                </label>
                <div className="relative">
                  <input
                    type={showApiKey ? 'text' : 'password'}
                    value={form.apiKey}
                    onChange={(e) => setForm((prev) => ({ ...prev, apiKey: e.target.value }))}
                    placeholder={editingCard ? '留空则保持原值' : '输入您的 API Key'}
                    className={`${INPUT_CLASS} pr-10`}
                  />
                  <button
                    type="button"
                    onClick={() => setShowApiKey(!showApiKey)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                      transition-colors hover:text-slate-600 dark:hover:text-slate-300"
                  >
                    {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>
              <div>
                <label className={LABEL_CLASS}>聊天模型 <span className="text-red-500">*</span></label>
                {chatModelOptions ? (
                  <select
                    value={form.model}
                    onChange={(e) => setForm((prev) => ({ ...prev, model: e.target.value }))}
                    className={INPUT_CLASS}
                  >
                    {chatModelOptions.map((m) => (
                      <option key={m.value} value={m.value}>{m.label}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    type="text"
                    value={form.model}
                    onChange={(e) => setForm((prev) => ({ ...prev, model: e.target.value }))}
                    placeholder="例如: qwen3.5-flash"
                    className={INPUT_CLASS}
                  />
                )}
              </div>

              <div className="rounded-lg border border-slate-200 p-3 dark:border-slate-600">
                <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                  <input
                    type="checkbox"
                    checked={form.supportsEmbedding}
                    onChange={(e) => setForm((prev) => ({ ...prev, supportsEmbedding: e.target.checked }))}
                    className="h-4 w-4 rounded border-slate-300 text-primary-600 focus:ring-primary-500"
                  />
                  该服务支持向量模型（用于知识库）
                </label>
                {form.supportsEmbedding && (
                  <div className="mt-3 grid grid-cols-2 gap-3">
                    <div>
                      <label className={LABEL_CLASS}>向量模型 <span className="text-red-500">*</span></label>
                      {embeddingModelOptions ? (
                        <select
                          value={form.embeddingModel}
                          onChange={(e) => setForm((prev) => ({ ...prev, embeddingModel: e.target.value }))}
                          className={INPUT_CLASS}
                        >
                          {embeddingModelOptions.map((m) => (
                            <option key={m.value} value={m.value}>{m.label}</option>
                          ))}
                        </select>
                      ) : (
                        <input
                          type="text"
                          value={form.embeddingModel}
                          onChange={(e) => setForm((prev) => ({ ...prev, embeddingModel: e.target.value }))}
                          placeholder="例如: text-embedding-v3"
                          className={INPUT_CLASS}
                        />
                      )}
                    </div>
                    <div>
                      <label className={LABEL_CLASS}>向量维度</label>
                      <input
                        type="text"
                        value={form.embeddingDimensions}
                        onChange={(e) => setForm((prev) => ({ ...prev, embeddingDimensions: e.target.value }))}
                        placeholder="例如: 1024"
                        className={INPUT_CLASS}
                      />
                    </div>
                  </div>
                )}
              </div>

              <div>
                <label className={LABEL_CLASS}>Temperature <span className="font-normal text-slate-400">(可选, 默认 0.2)</span></label>
                <input
                  type="text"
                  value={form.temperature}
                  onChange={(e) => setForm((prev) => ({ ...prev, temperature: e.target.value }))}
                  placeholder="0.2"
                  className={INPUT_CLASS}
                />
              </div>
            </div>

            <div className="mt-6 flex items-center justify-end gap-2">
              <button
                onClick={() => setCardFormOpen(false)}
                disabled={formSaving}
                className="inline-flex h-9 items-center rounded-lg px-4 text-sm font-medium
                  text-slate-600 transition-colors hover:bg-slate-100 disabled:opacity-50
                  dark:text-slate-300 dark:hover:bg-slate-700"
              >
                取消
              </button>
              <button
                onClick={handleSaveCard}
                disabled={formSaving}
                className="inline-flex h-9 items-center gap-2 rounded-lg px-4 text-sm font-semibold
                  bg-gradient-to-r from-primary-500 to-primary-600 text-white
                  shadow-md shadow-primary-500/25 transition-all hover:from-primary-600
                  hover:to-primary-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {formSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle className="h-4 w-4" />}
                {editingCard ? '保存修改' : '创建卡片'}
              </button>
            </div>
          </motion.div>
        </div>
      )}

      <ConfirmDialog
        open={deleteConfirmId != null}
        title="删除模型卡片"
        message="删除后，使用该卡片作为默认模型的 AI 功能将提示未配置。确定要删除这张卡片吗？"
        confirmText="确认删除"
        confirmVariant="danger"
        loading={deleting}
        onConfirm={handleDeleteCard}
        onCancel={() => {
          if (!deleting) {
            setDeleteConfirmId(null);
          }
        }}
      />

      {/* Toast notification */}
      {toast && (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: 20 }}
          className={`fixed bottom-6 right-6 z-50 flex items-center gap-2 rounded-lg px-4 py-3
            text-sm font-medium shadow-lg ${
              toast.type === 'success'
                ? 'bg-emerald-500 text-white'
                : 'bg-red-500 text-white'
            }`}
        >
          {toast.type === 'success'
            ? <CheckCircle className="h-4 w-4" />
            : <XCircle className="h-4 w-4" />
          }
          {toast.message}
        </motion.div>
      )}
    </div>
  );
}
