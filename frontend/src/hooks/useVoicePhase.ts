import { useCallback, useState } from 'react';
import type { VoiceInterviewPhase } from '../types/voiceInterview';
import { phaseLabelOf, resolvePhase } from '../utils/voiceInterview';

export interface VoicePhaseState {
  /** 当前已采纳的合法阶段。 */
  phase: VoiceInterviewPhase;
  /** 当前阶段的展示文案。 */
  label: string;
  /** 非空表示最近一次同步收到的阶段非法，UI 需要给出提示，正常情况下为 null。 */
  phaseWarning: string | null;
  isCompleted: boolean;
  /** 用服务端下发的值同步阶段；非法值会被丢弃并保留上一个合法阶段。 */
  syncPhase: (raw: unknown) => void;
  /** 新会话开始时重置。 */
  resetPhase: () => void;
}

interface InternalState {
  phase: VoiceInterviewPhase;
  warning: string | null;
}

/**
 * 语音面试阶段状态（FEDGE-004）。
 *
 * 阶段来源只有服务端，所以这里不是「状态机执行器」，而是「状态机校验器 + 持有者」：
 * 页面不再自己持有一个裸 string，非法迁移统一收敛成 `phaseWarning` 交给 UI。
 *
 * 阶段与告警放在同一个 state 里，避免两次 setState 之间出现中间态；
 * 迁移规则见 `utils/voiceInterview.ts`，与后端 `VoiceInterviewService.getNextPhase` 对应。
 */
export function useVoicePhase(initialPhase: VoiceInterviewPhase = 'INTRO'): VoicePhaseState {
  const [state, setState] = useState<InternalState>({ phase: initialPhase, warning: null });

  const syncPhase = useCallback((raw: unknown) => {
    setState((previous) => {
      const result = resolvePhase(raw, previous.phase);
      return { phase: result.phase, warning: result.warning };
    });
  }, []);

  const resetPhase = useCallback(() => {
    setState({ phase: initialPhase, warning: null });
  }, [initialPhase]);

  return {
    phase: state.phase,
    label: phaseLabelOf(state.phase),
    phaseWarning: state.warning,
    isCompleted: state.phase === 'COMPLETED',
    syncPhase,
    resetPhase,
  };
}
