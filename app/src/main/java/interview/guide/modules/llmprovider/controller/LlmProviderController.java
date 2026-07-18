package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController//组合注解 = @Controller + @ResponseBody。标记为 REST 控制器，所有方法返回值自动序列化为 JSON（而非视图名）
@RequestMapping("/api/llm-provider")//定义该控制器下所有接口的基础路径前缀。例如 listProviders 的完整路径是 /api/llm-provider/list。
@RequiredArgsConstructor//Lombok 注解。自动生成包含所有 final 字段的构造函数。配合 Spring 实现构造器注入（推荐的依赖注入方式，优于 @Autowired 字段注入）。
@Slf4j//Lombok 注解。自动生成 private static final Logger log = LoggerFactory.getLogger(...) 日志对象。
public class LlmProviderController {

  private final LlmProviderConfigService configService;
    /*
    声明一个 final 的服务层依赖。
    由于 @RequiredArgsConstructor，Lombok 会生成 LlmProviderController(LlmProviderConfigService configService) 构造函数。
    Spring 容器启动时会自动创建 LlmProviderConfigService 的实现类实例，并通过构造函数注入到这里。这就是 IoC 的核心体现。*/

  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<ProviderDTO>> listProviders() {
    return Result.success(configService.listProviders());
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<ProviderDTO> getProvider(@PathVariable String id) {
    return Result.success(configService.getProvider(id));
  }

//@RequestBody: 将 HTTP 请求体（JSON）反序列化为 CreateProviderRequest 对象。
//@Valid: 触发 Bean Validation。如果 DTO 上的 @NotBlank, @Size 等校验失败，Spring 会自动抛出 MethodArgumentNotValidException（通常由全局异常处理器捕获并返回 400 错误）。
  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
    configService.createProvider(request);
    return Result.success();
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateProvider(@PathVariable String id,
      @RequestBody UpdateProviderRequest request) {
    configService.updateProvider(id, request);
    return Result.success();
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> deleteProvider(@PathVariable String id) {
    configService.deleteProvider(id);
    return Result.success();
  }

  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testProvider(@PathVariable String id) {
    return Result.success(configService.testProvider(id));
  }

  @PostMapping("/reload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> reloadProviders() {
    configService.reloadProviders();
    return Result.success();
  }

  @GetMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<DefaultProviderDTO> getDefaultProvider() {
    return Result.success(configService.getDefaultProvider());
  }

  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultProvider(request);
    return Result.success();
  }

  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
    configService.updateDefaultEmbeddingProvider(request);
    return Result.success();
  }

  // ===== Voice ASR/TTS Config ===== asr,tts是语音文本转换模型

  @GetMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<AsrConfigDTO> getAsrConfig() {
    return Result.success(configService.getAsrConfig());
  }

  @PutMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateAsrConfig(@RequestBody AsrConfigRequest request) {
    configService.updateAsrConfig(request);
    return Result.success();
  }

  @GetMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<TtsConfigDTO> getTtsConfig() {
    return Result.success(configService.getTtsConfig());
  }

  @PutMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateTtsConfig(@RequestBody TtsConfigRequest request) {
    configService.updateTtsConfig(request);
    return Result.success();
  }

  @PostMapping("/voice/asr/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testAsrConfig() {
    return Result.success(configService.testAsrConfig());
  }
}
