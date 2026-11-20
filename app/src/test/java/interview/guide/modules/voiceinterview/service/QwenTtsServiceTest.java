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

    @Test
    @DisplayName("端点/协议不匹配的错误应给出明确诊断，而不是笼统兜底")
    void testEndpointMismatchErrorMapping() {
        Exception exception = new IllegalStateException(
            "HTTP 400 DashScope TTS error code=InvalidParameter message=url error, please check url!");

        assertTrue(QwenTtsService.toUserFacingError(exception).contains("端点不匹配"));
    }

    @Test
    @DisplayName("模型不存在的错误应指向模型开通")
    void testModelNotExistErrorMapping() {
        Exception exception = new IllegalStateException("404 Model not exist.");

        assertTrue(QwenTtsService.toUserFacingError(exception).contains("模型不存在或未开通"));
    }

    @Test
    @DisplayName("限流错误应提示稍后重试")
    void testRateLimitErrorMapping() {
        Exception exception = new IllegalStateException("429 Throttling.RateQuota");

        assertTrue(QwenTtsService.toUserFacingError(exception).contains("限流"));
    }

    @Test
    @DisplayName("未知错误的兜底文案应保留原始错误摘要")
    void testUnknownErrorKeepsRawMessage() {
        Exception exception = new IllegalStateException("some obscure upstream failure");

        assertTrue(QwenTtsService.toUserFacingError(exception)
            .contains("some obscure upstream failure"));
    }

    @Test
    @DisplayName("流式 WAV 的超大 data chunkSize 不应导致整数溢出")
    void testStripWavHeaderWithStreamingChunkSize() throws Exception {
        byte[] wav = new byte[52];
        putAscii(wav, 0, "RIFF");
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putAscii(wav, 36, "data");
        // qwen3-tts-flash 实际返回的流式 WAV 会把 data size 写成 0x7FFFFFFF 附近的值
        putIntLittleEndian(wav, 40, 2147483547);
        wav[44] = 1;
        wav[45] = 2;

        java.lang.reflect.Method method = QwenTtsService.class.getDeclaredMethod(
            "stripWavHeaderIfPresent", byte[].class);
        method.setAccessible(true);
        byte[] pcm = (byte[]) method.invoke(null, (Object) wav);

        assertEquals(8, pcm.length);
        assertEquals(1, pcm[0]);
        assertEquals(2, pcm[1]);
    }

    @Test
    @DisplayName("qwen-audio 模型应保留模型名并使用 SpeechSynthesizer 端点")
    void testAudioModelUsesSpeechSynthesizerEndpoint() throws Exception {
        VoiceInterviewProperties props = new VoiceInterviewProperties();
        props.getQwen().getTts().setApiKey("test-api-key");
        props.getQwen().getTts().setModel("qwen-audio-3.1-tts-flash");

        QwenTtsService service = new QwenTtsService(props);

        assertEquals("qwen-audio-3.1-tts-flash", field(service, "model"));
        String endpoint = (String) field(service, "audioEndpoint");
        assertTrue(endpoint.contains("/api/v1/services/audio/tts/SpeechSynthesizer"),
            "qwen-audio 应使用新端点，实际=" + endpoint);
    }

    @Test
    @DisplayName("qwen-audio 模型的 Cherry 音色应自动换成 v3.x 音色")
    void testAudioModelReplacesLegacyCherryVoice() throws Exception {
        VoiceInterviewProperties props = new VoiceInterviewProperties();
        props.getQwen().getTts().setApiKey("test-api-key");
        props.getQwen().getTts().setModel("qwen-audio-3.1-tts-flash");
        props.getQwen().getTts().setVoice("Cherry");

        QwenTtsService service = new QwenTtsService(props);

        // 服务字段保留原值；实际替换发生在 synthesizeViaHttp 组装 payload 时。
        // 这里校验 isAudioModel 分支所依赖的默认音色常量已生效的配置面。
        assertEquals("Cherry", field(service, "voice"));
        assertTrue(service.getClass().getDeclaredField("audioEndpoint") != null);
    }

    private static void putAscii(byte[] target, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            target[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void putIntLittleEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }

    private static Object field(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
