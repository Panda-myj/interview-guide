package interview.guide.modules.llmprovider.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderConfigService 测试")
class LlmProviderConfigServiceTest {

    private static final String RUNTIME_CONFIG_HEADER = """
        app:
          ai:
            providers: {}
        """;

    @Mock private LlmProviderProperties properties;
    @Mock private LlmProviderRegistry registry;
    @Mock private VoiceInterviewProperties voiceProperties;
    @Mock private QwenAsrService asrService;
    @Mock private QwenTtsService ttsService;

    private LlmProviderConfigService service;
    private Path runtimeConfigFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        runtimeConfigFile = tempDir.resolve("runtime-config.yml");
        Files.writeString(runtimeConfigFile, RUNTIME_CONFIG_HEADER, StandardCharsets.UTF_8);

        when(properties.getConfigYamlPath()).thenReturn(runtimeConfigFile.toString());

        service = newService(voiceProperties);
    }

    private LlmProviderConfigService newService(VoiceInterviewProperties voiceProps) {
        return new LlmProviderConfigService(
            properties,
            registry,
            voiceProps,
            asrService,
            ttsService
        );
    }

    @Nested
    @DisplayName("启动校验")
    class Bootstrap {

        @Test
        @DisplayName("validateWritablePaths 对不可创建的父目录 fail-fast")
        void validateWritablePathsFailsFastWhenParentUnwritable(@TempDir Path tempDir) throws IOException {
            Path sentinel = tempDir.resolve("not-a-dir");
            Files.writeString(sentinel, "");
            Path unreachableYaml = sentinel.resolve("child/llm-providers.yml");

            when(properties.getConfigYamlPath()).thenReturn(unreachableYaml.toString());

            LlmProviderConfigService failing = newService(voiceProperties);

            assertThrows(BusinessException.class, failing::validateWritablePaths);
        }

        @Test
        @DisplayName("未配置 config-yaml-path 时启动校验不创建任何文件")
        void validateWritablePathsSkipsWhenNotConfigured(@TempDir Path tempDir) throws IOException {
            when(properties.getConfigYamlPath()).thenReturn(null);

            LlmProviderConfigService noFileService = newService(voiceProperties);

            assertDoesNotThrow(noFileService::validateWritablePaths);
            try (var entries = Files.list(tempDir)) {
                assertEquals(0, entries.count());
            }
        }

        @Test
        @DisplayName("拒绝把运行期配置写回 classpath / 源码目录")
        void rejectsClasspathLocation(@TempDir Path tempDir) {
            Path sourceTreePath = tempDir.resolve("src/main/resources/application.yml");
            when(properties.getConfigYamlPath()).thenReturn(sourceTreePath.toString());

            LlmProviderConfigService sourceTreeService = newService(voiceProperties);

            BusinessException exception = assertThrows(BusinessException.class,
                sourceTreeService::validateWritablePaths);
            assertEquals(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED.getCode(), exception.getCode());
        }
    }

    @Nested
    @DisplayName("基础行为")
    class BasicBehavior {

        @Test
        @DisplayName("maskApiKey 返回脱敏值")
        void maskApiKeyReturnsMaskedValue() {
            assertEquals("sk-***xyz", service.maskApiKey("sk-abcdefxyz"));
            assertEquals("***", service.maskApiKey("ab"));
            assertEquals("abc***fgh", service.maskApiKey("abcdefgh"));
        }

        @Test
        @DisplayName("listProviders 在 providers 为空时返回空列表")
        void listProvidersReturnsEmptyWhenProvidersNull() {
            when(properties.getProviders()).thenReturn(null);

            assertTrue(service.listProviders().isEmpty());
        }

        @Test
        @DisplayName("getProvider 对未知 provider 抛出异常")
        void getProviderThrowsWhenProviderMissing() {
            when(properties.getProviders()).thenReturn(new HashMap<>());

            assertThrows(BusinessException.class, () -> service.getProvider("unknown"));
        }

        @Test
        @DisplayName("GLM base-url 测试连接不应重复拼接 /v1")
        void buildConnectivityUrlsAvoidsDoubleVersionForGlm() throws Exception {
            List<String> urls = invokeConnectivityUrls("https://open.bigmodel.cn/api/coding/paas/v4");

            assertEquals(List.of("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"), urls);
        }

        @Test
        @DisplayName("测试连接请求体不再强制携带 temperature")
        void connectivityRequestBodyOmitsTemperature() throws Exception {
            Map<String, Object> body = invokeConnectivityRequestBody("kimi-latest");

            assertEquals("kimi-latest", body.get("model"));
            assertEquals(1, body.get("max_tokens"));
            assertTrue(body.containsKey("messages"));
            assertTrue(!body.containsKey("temperature"));
        }
    }

    @Nested
    @DisplayName("Provider 管理")
    class ProviderManagement {

        @Test
        @DisplayName("createProvider 对重复 id 抛出异常")
        void createProviderThrowsForDuplicateId() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new HashMap<>();
            providers.put("existing", createProviderConfig("http://localhost:1234", "key", "model", null));
            when(properties.getProviders()).thenReturn(providers);

            CreateProviderRequest request = new CreateProviderRequest(
                "existing",
                "http://localhost:1234",
                "key",
                "model",
                null,
                null
            );

            assertThrows(BusinessException.class, () -> service.createProvider(request));
        }

        @Test
        @DisplayName("deleteProvider 删除默认 provider 时抛出异常")
        void deleteProviderThrowsForDefaultProvider() {
            when(properties.getDefaultProvider()).thenReturn("dashscope");

            assertThrows(BusinessException.class, () -> service.deleteProvider("dashscope"));
        }

        @Test
        @DisplayName("updateProvider 允许清空 embedding model")
        void updateProviderAllowsClearingEmbeddingModel() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, "", null));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 对纯空白 embedding model 等价于清空")
        void updateProviderTreatsBlankEmbeddingModelAsClear() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, "   ", null));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 拒绝空串 baseUrl / model / apiKey")
        void updateProviderRejectsBlankRequiredFields() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope",
                createProviderConfig("https://dashscope.aliyuncs.com", "secret", "qwen-plus", null));
            when(properties.getProviders()).thenReturn(providers);

            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("", null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("   ", null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, null, "", null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, "  ", null, null, null)));
        }
    }

    @Nested
    @DisplayName("全局默认 Provider")
    class DefaultProviderBehavior {

        @Test
        @DisplayName("updateDefaultProvider 拒绝未知 provider")
        void updateDefaultProviderRejectsUnknownProvider() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            when(properties.getProviders()).thenReturn(providers);

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateDefaultProvider(new DefaultProviderDTO("unknown"))
            );

            assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), exception.getCode());
            verify(properties, never()).setDefaultProvider("unknown");
            verify(registry, never()).reload();
        }

        @Test
        @DisplayName("updateDefaultProvider 写入新的默认 provider")
        void updateDefaultProviderPersistsValue() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            providers.put("glm", createProviderConfig("https://open.bigmodel.cn/api/coding/paas/v4", "key", "glm-4-flash", null));
            when(properties.getProviders()).thenReturn(providers);

            service.updateDefaultProvider(new DefaultProviderDTO("glm"));

            verify(properties).setDefaultProvider("glm");
            verify(registry).reload();
        }
    }

    @Nested
    @DisplayName("本机配置文件写入")
    class YamlMutations {

        @Test
        @DisplayName("mutateYaml 在文件不存在时创建新文件")
        void createsNewFileWhenMissing(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("new-config.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());

            LlmProviderConfigService yamlService = newService(voiceProperties);

            when(properties.getProviders()).thenReturn(new HashMap<>());

            yamlService.createProvider(
                new CreateProviderRequest("kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));

            assertTrue(Files.exists(yamlFile));
            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("kimi"));
            assertTrue(content.contains("kimi-latest"));
        }

        @Test
        @DisplayName("mutateYaml 保留已有 YAML 结构")
        void preservesExistingStructure(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("config.yml");
            Files.writeString(yamlFile, """
                app:
                  ai:
                    default-provider: dashscope
                  voice-interview:
                    qwen:
                      asr:
                        model: qwen3-asr-flash-realtime
                """);

            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());

            LlmProviderConfigService yamlService = newService(voiceProperties);

            when(properties.getProviders()).thenReturn(new HashMap<>());

            yamlService.createProvider(
                new CreateProviderRequest("kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));

            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("default-provider"));
            assertTrue(content.contains("qwen3-asr-flash-realtime"));
            assertTrue(content.contains("kimi"));
        }

        @Test
        @DisplayName("yamlPath 为 null 时拒绝静默丢弃 Provider 配置")
        void nullYamlPathIsRejected() {
            when(properties.getConfigYamlPath()).thenReturn(null);

            LlmProviderConfigService nullYamlService = newService(voiceProperties);
            when(properties.getProviders()).thenReturn(new HashMap<>());

            assertThrows(BusinessException.class, () -> nullYamlService.createProvider(
                new CreateProviderRequest("test", "http://localhost", "key", "model", null, null)));
        }

        @Test
        @DisplayName("YAML 只写 ${ENV} 占位符，不出现明文 API Key")
        void writesPlaceholderOnly(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("placeholder.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getProviders()).thenReturn(new HashMap<>());

            LlmProviderConfigService yamlService = newService(voiceProperties);
            yamlService.createProvider(new CreateProviderRequest(
                "kimi", "https://api.moonshot.cn/v1", "sk-live-plaintext-0001", "kimi-latest", null, null));

            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("${PROVIDER_KIMI_API_KEY}"));
            assertFalse(content.contains("sk-live-plaintext-0001"));
        }

        @Test
        @DisplayName("写入使用原子替换，不残留临时文件")
        void keepsNoTemporaryFiles(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("atomic.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getProviders()).thenReturn(new HashMap<>());

            LlmProviderConfigService yamlService = newService(voiceProperties);
            yamlService.createProvider(new CreateProviderRequest(
                "kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));
            yamlService.createProvider(new CreateProviderRequest(
                "glm", "https://open.bigmodel.cn/api/coding/paas/v4", "key", "glm-4-flash", null, null));

            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("kimi"));
            assertTrue(content.contains("glm"));
            try (var entries = Files.list(tempDir)) {
                assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        }

        @Test
        @DisplayName("未配置 config-yaml-path 时语音配置只改内存并给出告警")
        void voiceConfigSkipsFileWhenPathMissing(@TempDir Path tempDir) throws IOException {
            when(properties.getConfigYamlPath()).thenReturn(null);

            VoiceInterviewProperties realVoice = new VoiceInterviewProperties();
            LlmProviderConfigService memoryOnlyService = newService(realVoice);

            assertDoesNotThrow(() -> memoryOnlyService.updateAsrConfig(
                new AsrConfigRequest("wss://8.8.8.8/realtime", "qwen3-asr", null, null, null,
                    null, null, null, null, null)));
            try (var entries = Files.list(tempDir)) {
                assertEquals(0, entries.count());
            }
        }
    }

    @Nested
    @DisplayName("敏感信息落盘防护")
    class SensitiveInfoProtection {

        @Test
        @DisplayName("默认不写 ENV 文件，密钥不落盘")
        void neverWritesEnvFile(@TempDir Path tempDir) throws IOException {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "AI_BAILIAN_API_KEY=existing\n", StandardCharsets.UTF_8);
            lenient().when(properties.getConfigEnvPath()).thenReturn(envFile.toString());

            Path yamlFile = tempDir.resolve("runtime-config.yml");
            Files.writeString(yamlFile, RUNTIME_CONFIG_HEADER, StandardCharsets.UTF_8);
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());

            VoiceInterviewProperties realVoice = new VoiceInterviewProperties();
            LlmProviderConfigService sensitiveService = newService(realVoice);

            String secret = "sk-live-should-never-be-persisted";
            sensitiveService.updateAsrConfig(
                new AsrConfigRequest(null, null, secret, null, null, null, null, null, null, null));

            assertEquals("AI_BAILIAN_API_KEY=existing\n",
                Files.readString(envFile, StandardCharsets.UTF_8));
            assertFalse(Files.readString(yamlFile, StandardCharsets.UTF_8).contains(secret));
            assertTrue(Files.readString(yamlFile, StandardCharsets.UTF_8)
                .contains("${AI_BAILIAN_API_KEY}"));
        }

        @Test
        @DisplayName("启动时把历史明文 api-key 替换为占位符并保留其余配置")
        void sanitizesLegacyPlaintextKeys(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("legacy.yml");
            Files.writeString(yamlFile, """
                app:
                  ai:
                    providers:
                      kimi:
                        base-url: https://api.moonshot.cn/v1
                        api-key: sk-legacy-plaintext-123456
                        model: kimi-latest
                  voice-interview:
                    qwen:
                      asr:
                        url: wss://8.8.8.8/realtime
                        api-key: sk-voice-plaintext-abcdef
                """, StandardCharsets.UTF_8);
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());

            LlmProviderConfigService legacyService = newService(voiceProperties);
            legacyService.validateWritablePaths();

            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertFalse(content.contains("sk-legacy-plaintext-123456"));
            assertFalse(content.contains("sk-voice-plaintext-abcdef"));
            assertTrue(content.contains("${PROVIDER_KIMI_API_KEY}"));
            assertTrue(content.contains("${AI_BAILIAN_API_KEY}"));
            assertTrue(content.contains("base-url"));
            assertTrue(content.contains("kimi-latest"));
        }

        @Test
        @DisplayName("日志中不出现明文 API Key")
        void logsNeverContainPlaintextKey() {
            org.slf4j.Logger candidate = LoggerFactory.getLogger(LlmProviderConfigService.class);
            Assumptions.assumeTrue(candidate instanceof Logger,
                "当前运行时未绑定 logback，跳过日志断言");
            Logger logger = (Logger) candidate;
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                VoiceInterviewProperties realVoice = new VoiceInterviewProperties();
                LlmProviderConfigService loggingService = newService(realVoice);
                String secret = "sk-log-should-not-appear-987654";
                loggingService.updateAsrConfig(
                    new AsrConfigRequest(null, null, secret, null, null, null, null, null, null, null));

                assertTrue(appender.list.stream()
                    .noneMatch(event -> event.getFormattedMessage().contains(secret)));
                assertTrue(appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("sk-***654")));
            } finally {
                logger.detachAppender(appender);
            }
        }
    }

    @Nested
    @DisplayName("并发与文件权限")
    class ConcurrencyAndPermissions {

        @Test
        @DisplayName("并发创建不同 Provider 不会互相覆盖")
        void concurrentWritesDoNotLoseUpdates(@TempDir Path tempDir) throws Exception {
            Path yamlFile = tempDir.resolve("concurrent.yml");
            Files.writeString(yamlFile, RUNTIME_CONFIG_HEADER, StandardCharsets.UTF_8);
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());

            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            when(properties.getProviders()).thenReturn(providers);

            LlmProviderConfigService concurrentService = newService(voiceProperties);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread alpha = new Thread(() -> createProviderQuietly(concurrentService, failure,
                "alpha", "https://api.example.com/v1"));
            Thread beta = new Thread(() -> createProviderQuietly(concurrentService, failure,
                "beta", "https://api.example.com/v1"));
            alpha.start();
            beta.start();
            alpha.join();
            beta.join();

            assertNull(failure.get());
            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("alpha"));
            assertTrue(content.contains("beta"));
        }

        @Test
        @DisplayName("写入后的配置文件权限为仅所有者可读写")
        void configFileIsOwnerOnly(@TempDir Path tempDir) throws IOException {
            Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "当前文件系统不支持 POSIX 权限");

            Path yamlFile = tempDir.resolve("private.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getProviders()).thenReturn(new HashMap<>());

            LlmProviderConfigService privateService = newService(voiceProperties);
            privateService.createProvider(new CreateProviderRequest(
                "kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(yamlFile);
            assertEquals(PosixFilePermissions.fromString("rw-------"), permissions);
        }

        @Test
        @DisplayName("热加载失败只记录告警，不影响已完成的配置写入")
        void reloadFailureDoesNotBreakWrites(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("reload.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getProviders()).thenReturn(new HashMap<>());
            doThrow(new IllegalStateException("cache rebuild failed")).when(registry).reload();

            LlmProviderConfigService flakyService = newService(voiceProperties);

            assertDoesNotThrow(() -> flakyService.createProvider(new CreateProviderRequest(
                "kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null)));
            assertTrue(Files.readString(yamlFile, StandardCharsets.UTF_8).contains("kimi"));
        }
    }

    @Nested
    @DisplayName("Provider 连接安全")
    class ProviderConnectivitySecurity {

        @Test
        @DisplayName("默认拒绝本机 Provider 测试连接，不发起请求")
        void rejectsLocalProviderEndpoint() {
            LlmProviderProperties.SecurityConfig security = new LlmProviderProperties.SecurityConfig();
            when(properties.getSecurity()).thenReturn(security);

            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("local", createProviderConfig("http://127.0.0.1:1234", "key", "model", null));
            when(properties.getProviders()).thenReturn(providers);

            ProviderTestResult result = service.testProvider("local");

            assertFalse(result.success());
            assertTrue(result.message().contains("拒绝"));
        }

        @Test
        @DisplayName("默认拒绝指向内网地址的 ASR 测试连接")
        void rejectsInternalAsrEndpoint() {
            LlmProviderProperties.SecurityConfig security = new LlmProviderProperties.SecurityConfig();
            when(properties.getSecurity()).thenReturn(security);

            VoiceInterviewProperties realVoice = new VoiceInterviewProperties();
            realVoice.getQwen().getAsr().setUrl("ws://10.0.0.8:9000/asr");
            realVoice.getQwen().getAsr().setModel("qwen3-asr");

            LlmProviderConfigService voiceService = newService(realVoice);

            ProviderTestResult result = voiceService.testAsrConfig();

            assertFalse(result.success());
            assertTrue(result.message().contains("拒绝"));
        }
    }

    private void createProviderQuietly(
        LlmProviderConfigService target,
        AtomicReference<Throwable> failure,
        String id,
        String baseUrl) {
        try {
            target.createProvider(new CreateProviderRequest(
                id, baseUrl, "key", "model-name", null, null));
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private LlmProviderProperties.ProviderConfig createProviderConfig(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel
    ) {
        LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setEmbeddingModel(embeddingModel);
        return config;
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeConnectivityUrls(String baseUrl)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestUrls",
            String.class
        );
        method.setAccessible(true);
        return (List<String>) method.invoke(service, baseUrl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeConnectivityRequestBody(String model)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestRequestBody",
            String.class
        );
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, model);
    }
}
