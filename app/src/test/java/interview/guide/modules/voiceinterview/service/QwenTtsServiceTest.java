package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenTtsService Unit Tests")
class QwenTtsServiceTest {

    private QwenTtsService ttsService;

    @BeforeEach
    void setUp() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig tts = properties.getQwen().getTts();
        tts.setModel("qwen3-tts-flash-realtime");
        tts.setApiKey("test-api-key");
        tts.setVoice("Cherry");
        tts.setFormat("pcm");
        tts.setSampleRate(16000);
        tts.setMode("server_commit");
        tts.setLanguageType("Chinese");
        tts.setSpeechRate(1.0f);
        tts.setVolume(60);

        ttsService = new QwenTtsService(properties);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> ttsService.init());
    }

    @Test
    @DisplayName("Should return empty array for empty text")
    void testSynthesizeEmptyText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for null text")
    void testSynthesizeNullText() {
        ttsService.init();

        byte[] result = ttsService.synthesize(null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for whitespace text")
    void testSynthesizeWhitespaceText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("   ");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() {
        ttsService.init();

        // Destroy should cleanup resources without error
        assertDoesNotThrow(() -> ttsService.destroy());
    }

    @Test
    @DisplayName("reload 应更新所有 TTS 配置字段")
    void testReloadUpdatesAllFields() throws Exception {
        VoiceInterviewProperties newProps = new VoiceInterviewProperties();
        VoiceInterviewProperties.QwenTtsConfig newTts = newProps.getQwen().getTts();
        newTts.setModel("new-tts-model");
        newTts.setApiKey("new-api-key");
        newTts.setVoice("Serena");
        newTts.setFormat("mp3");
        newTts.setSampleRate(48000);
        newTts.setMode("user_commit");
        newTts.setLanguageType("English");
        newTts.setSpeechRate(1.5f);
        newTts.setVolume(80);

        ttsService.reload(newProps);

        assertEquals("new-tts-model", field(ttsService, "model"));
        assertEquals("new-api-key", field(ttsService, "apiKey"));
        assertEquals("Serena", field(ttsService, "voice"));
        assertEquals("pcm", field(ttsService, "format"));
        // HTTP TTS 当前统一返回 24kHz PCM，配置的 48000 会被忽略
        assertEquals(24000, field(ttsService, "sampleRate"));
        assertEquals("user_commit", field(ttsService, "mode"));
        assertEquals("English", field(ttsService, "languageType"));
        assertEquals(1.5f, field(ttsService, "speechRate"));
        assertEquals(80, field(ttsService, "volume"));
    }

    @Test
    @DisplayName("非 24kHz 的采样率配置应被忽略，固定使用 24kHz 输出")
    void testSampleRateClampedTo24k() throws Exception {
        VoiceInterviewProperties props = new VoiceInterviewProperties();
        props.getQwen().getTts().setApiKey("test-api-key");
        props.getQwen().getTts().setSampleRate(16000);

        QwenTtsService service = new QwenTtsService(props);

        assertEquals(24000, field(service, "sampleRate"));
    }

    @Test
    @DisplayName("旧实时模型名应自动切换到对应的 HTTP TTS 模型")
    void testRealtimeModelNormalizedToHttpModel() throws Exception {
        VoiceInterviewProperties props = new VoiceInterviewProperties();
        props.getQwen().getTts().setApiKey("test-api-key");
        props.getQwen().getTts().setModel("qwen3-tts-flash-realtime");

        QwenTtsService service = new QwenTtsService(props);

        assertEquals("qwen3-tts-flash", field(service, "model"));
    }

    @Test
    @DisplayName("免费额度耗尽时应返回可操作的错误信息")
    void testFreeTierQuotaErrorMapping() {
        Exception exception = new IllegalStateException(
            "403 AllocationQuota.FreeTierOnly: Free quota exhausted");

        assertTrue(QwenTtsService.toUserFacingError(exception).contains("免费额度已用完"));
    }

    private static Object field(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
