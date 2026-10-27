import { useEffect, useRef, useState } from 'react';
import { Mic, MicOff } from 'lucide-react';

interface AudioRecorderProps {
  isRecording: boolean;
  disabled?: boolean;
  onRecordingChange: (isRecording: boolean) => void;
  onAudioData: (audioData: string) => void;
  onError?: (message: string) => void;
  onInteraction?: () => void;
}

export default function AudioRecorder({
  isRecording,
  disabled = false,
  onRecordingChange,
  onAudioData,
  onError,
  onInteraction,
}: AudioRecorderProps) {
  const [volume, setVolume] = useState(0);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const workletNodeRef = useRef<AudioWorkletNode | null>(null);
  const gainNodeRef = useRef<GainNode | null>(null);
  const mountedRef = useRef(true);
  const recordingActiveRef = useRef(false);
  const startingRef = useRef(false);

  const cleanupRecordingResources = (updateVolume = true) => {
    recordingActiveRef.current = false;

    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }

    if (workletNodeRef.current) {
      try {
        workletNodeRef.current.port.onmessage = null;
        workletNodeRef.current.disconnect();
      } catch {
        // Ignore disconnect errors during teardown.
      }
      workletNodeRef.current = null;
    }

    if (gainNodeRef.current) {
      try {
        gainNodeRef.current.disconnect();
      } catch {
        // Ignore disconnect errors during teardown.
      }
      gainNodeRef.current = null;
    }

    if (analyserRef.current) {
      try {
        analyserRef.current.disconnect();
      } catch {
        // Ignore disconnect errors during teardown.
      }
      analyserRef.current = null;
    }

    if (mediaStreamRef.current) {
      mediaStreamRef.current.getTracks().forEach(track => track.stop());
      mediaStreamRef.current = null;
    }

    if (audioContextRef.current) {
      void audioContextRef.current.close().catch(() => undefined);
      audioContextRef.current = null;
    }

    if (updateVolume) {
      setVolume(0);
    }
  };

  const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunkSize = 0x8000;

    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.subarray(i, i + chunkSize);
      binary += String.fromCharCode(...chunk);
    }

    return btoa(binary);
  };

  const startRecording = async () => {
    if (startingRef.current) {
      return;
    }
    startingRef.current = true;

    try {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
        throw new Error('麦克风只允许在 HTTPS 或 localhost 页面使用');
      }
      if (!window.AudioContext || !window.AudioWorkletNode) {
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome 或 Edge');
      }

      const audioContext = new AudioContext();
      audioContextRef.current = audioContext;
      await audioContext.resume();

      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      if (!mountedRef.current) {
        stream.getTracks().forEach(track => track.stop());
        return;
      }
      mediaStreamRef.current = stream;

      if (!audioContext.audioWorklet) {
        await audioContext.close();
        throw new Error('当前浏览器不支持 AudioWorklet，请使用新版 Chrome 或 Edge');
      }

      const source = audioContext.createMediaStreamSource(stream);
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
      audioContextRef.current = audioContext;
      analyserRef.current = analyser;

      const dataArray = new Uint8Array(analyser.frequencyBinCount);
      intervalRef.current = setInterval(() => {
        if (!mountedRef.current || !analyserRef.current) {
          return;
        }
        analyserRef.current.getByteFrequencyData(dataArray);
        const average = dataArray.reduce((sum, value) => sum + value, 0) / dataArray.length;
        setVolume(average);
      }, 100);

      await audioContext.audioWorklet.addModule('/audio-worklet/pcm-processor.js');

      if (!mountedRef.current) {
        cleanupRecordingResources(false);
        return;
      }

      const workletNode = new AudioWorkletNode(audioContext, 'pcm-processor');
      workletNodeRef.current = workletNode;
      recordingActiveRef.current = true;

      workletNode.port.onmessage = event => {
        if (!mountedRef.current || !recordingActiveRef.current || !workletNodeRef.current) {
          return;
        }
        onAudioData(arrayBufferToBase64(event.data as ArrayBuffer));
      };

      const gainNode = audioContext.createGain();
      gainNode.gain.value = 0;
      gainNodeRef.current = gainNode;

      source.connect(workletNode);
      workletNode.connect(gainNode);
      gainNode.connect(audioContext.destination);

      if (mountedRef.current) {
        onRecordingChange(true);
      }
    } catch (error) {
      cleanupRecordingResources(mountedRef.current);
      if (!mountedRef.current) {
        return;
      }
      const message = error instanceof Error ? error.message : '无法访问麦克风，请检查权限设置';
      console.error('Error starting microphone capture:', error);
      onError?.(message);
    } finally {
      startingRef.current = false;
    }
  };

  const stopRecording = () => {
    startingRef.current = false;
    cleanupRecordingResources(mountedRef.current);
    if (isRecording) {
      onRecordingChange(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      stopRecording();
    };
  }, []);

  useEffect(() => {
    if ((disabled || !isRecording) && recordingActiveRef.current) {
      cleanupRecordingResources(mountedRef.current);
      if (isRecording) {
        onRecordingChange(false);
      }
    }
  }, [disabled, isRecording, onRecordingChange]);

  const toggleRecording = () => {
    onInteraction?.();
    if (disabled && !isRecording) {
      return;
    }
    if (isRecording) {
      stopRecording();
    } else {
      void startRecording();
    }
  };

  return (
    <div className="relative flex items-center justify-center">
      {isRecording && (
        <div
          className="absolute rounded-full border border-primary-500/50 pointer-events-none transition-all duration-75"
          style={{
            width: `${100 + (volume / 255) * 100}%`,
            height: `${100 + (volume / 255) * 100}%`,
            opacity: Math.max(0, 1 - (volume / 255) * 1.5),
          }}
        />
      )}

      <button
        onClick={toggleRecording}
        disabled={disabled && !isRecording}
        className={`
          relative z-10 w-16 h-16 rounded-full flex items-center justify-center
          transition-all duration-300 shadow-xl
          ${disabled && !isRecording ? 'opacity-50 cursor-not-allowed shadow-none' : ''}
          ${isRecording
            ? 'bg-primary-500 hover:bg-primary-600 shadow-primary-500/40'
            : 'bg-slate-700 hover:bg-slate-600 shadow-slate-900/50'
          }
        `}
        title={disabled && !isRecording ? '语音识别准备中' : isRecording ? '停止录音' : '开始说话'}
      >
        {isRecording ? (
          <Mic className="w-7 h-7 text-white" />
        ) : (
          <MicOff className="w-7 h-7 text-slate-300" />
        )}
      </button>
    </div>
  );
}
