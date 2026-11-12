import type { SkillDTO } from '../api/skill';
import {
  VOICE_INTERVIEW_PHASES,
  type VoiceInterviewPhase,
} from '../types/voiceInterview';

export function getTemplateName(skillId: string, skills: SkillDTO[]): string {
  return skills.find(s => s.id === skillId)?.name || skillId;
}

/**
 * 前端镜像的后端阶段迁移表（FEDGE-004）。
 *
 * 后端唯一来源：`VoiceInterviewService.getNextPhase`——
 * INTRO 依 tech/project/hr 是否启用直接跳到 TECH/PROJECT/HR/COMPLETED，
 * 之后同样逐段前进，最终落到 COMPLETED（吸收态）。
 * 因此这里允许“跳过未启用的环节”，但绝不允许回退。
 */
export const VOICE_PHASE_TRANSITIONS: Record<VoiceInterviewPhase, readonly VoiceInterviewPhase[]> = {
  INTRO: ['TECH', 'PROJECT', 'HR', 'COMPLETED'],
  TECH: ['PROJECT', 'HR', 'COMPLETED'],
  PROJECT: ['HR', 'COMPLETED'],
  HR: ['COMPLETED'],
  COMPLETED: [],
};

const PHASE_LABELS: Record<VoiceInterviewPhase, string> = {
  INTRO: '自我介绍',
  TECH: '技术问题',
  PROJECT: '项目深挖',
  HR: 'HR问题',
  COMPLETED: '面试结束',
};

export function isVoiceInterviewPhase(value: unknown): value is VoiceInterviewPhase {
  return typeof value === 'string'
    && (VOICE_INTERVIEW_PHASES as readonly string[]).includes(value);
}

/**
 * 是否允许 `from` → `to`。
 * 同阶段重复下发视为合法（服务端可能重发 SessionMeta），属于幂等而非状态迁移。
 */
export function canEnterPhase(from: VoiceInterviewPhase, to: VoiceInterviewPhase): boolean {
  return from === to || VOICE_PHASE_TRANSITIONS[from].includes(to);
}

/** 展示用文案；未知阶段不回退成原始字符串，避免把后端枚举值直接暴露给用户。 */
export function phaseLabelOf(phase: unknown): string {
  return isVoiceInterviewPhase(phase) ? PHASE_LABELS[phase] : '未知阶段';
}

export interface PhaseSyncResult {
  /** 采纳后的阶段：非法输入时保留上一次合法值。 */
  phase: VoiceInterviewPhase;
  /** 非法原因；null 表示本次同步合法。 */
  warning: string | null;
}

/**
 * 把服务端下发的阶段合并进当前状态。
 *
 * 未知阶段与阶段回退都被拒绝：重连时可能收到过期的 SessionMeta，
 * 直接采纳会把界面拉回上一环节，用户会看到阶段“倒退”。
 */
export function resolvePhase(raw: unknown, previous: VoiceInterviewPhase): PhaseSyncResult {
  if (!isVoiceInterviewPhase(raw)) {
    const received = raw == null || raw === '' ? '空值' : String(raw);
    return { phase: previous, warning: `收到未知的面试阶段「${received}」，已保持当前阶段。` };
  }
  if (!canEnterPhase(previous, raw)) {
    return {
      phase: previous,
      warning: `面试阶段不能从「${PHASE_LABELS[previous]}」回退到「${PHASE_LABELS[raw]}」，已忽略。`,
    };
  }
  return { phase: raw, warning: null };
}
