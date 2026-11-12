/**
 * 语音面试领域类型（FEDGE-004）。
 *
 * 阶段枚举与后端 `VoiceInterviewSessionEntity.InterviewPhase` 保持一致，
 * 页面与组件只引用这里的定义，避免多处字符串字面量漂移。
 */

export const VOICE_INTERVIEW_PHASES = ['INTRO', 'TECH', 'PROJECT', 'HR', 'COMPLETED'] as const;

export type VoiceInterviewPhase = (typeof VOICE_INTERVIEW_PHASES)[number];

/** WebSocket 连接状态。 */
export type VoiceConnectionStatus = 'disconnected' | 'connecting' | 'connected';

/** 开启语音面试时提交给后端的配置。 */
export interface VoiceInterviewConfig {
  skillId: string;
  difficulty?: string;
  techEnabled: boolean;
  projectEnabled: boolean;
  hrEnabled: boolean;
  plannedDuration: number;
  resumeId?: number;
  llmProvider?: string;
}

/** 对话气泡。 */
export interface VoiceMessage {
  role: 'user' | 'ai';
  text: string;
  id: string;
}
