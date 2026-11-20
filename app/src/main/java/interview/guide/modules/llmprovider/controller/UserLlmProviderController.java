package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.CurrentUserService;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UserDefaultRequest;
import interview.guide.modules.llmprovider.dto.UserProviderDTO;
import interview.guide.modules.llmprovider.dto.UserSaveProviderRequest;
import interview.guide.modules.llmprovider.service.UserLlmConfigService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一的用户级模型配置接口：所有登录用户（含管理员）使用同一套入口，
 * 管理并使用自己的多张模型卡片（文本 + 向量）与语音服务配置。
 * userId 一律来自认证上下文，不接受路径参数，用户只能访问自己的数据。
 */
@RestController
@RequestMapping("/api/user-llm-provider")
@RequiredArgsConstructor
public class UserLlmProviderController {

  private final UserLlmConfigService userLlmConfigService;
  private final CurrentUserService currentUserService;

  // ===== 模型卡片 =====

  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<List<UserProviderDTO>> listMyProviders() {
    return Result.success(
        userLlmConfigService.listMyProviders(currentUserService.getRequiredUserId()));
  }

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<UserProviderDTO> createMyProvider(
      @RequestBody @Valid UserSaveProviderRequest request) {
    return Result.success(
        userLlmConfigService.createMyProvider(currentUserService.getRequiredUserId(), request));
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> updateMyProvider(@PathVariable Long id,
      @RequestBody @Valid UserSaveProviderRequest request) {
    userLlmConfigService.updateMyProvider(currentUserService.getRequiredUserId(), id, request);
    return Result.success();
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> deleteMyProvider(@PathVariable Long id) {
    userLlmConfigService.deleteMyProvider(currentUserService.getRequiredUserId(), id);
    return Result.success();
  }

  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<ProviderTestResult> testMyProvider(@PathVariable Long id) {
    return Result.success(
        userLlmConfigService.testMyProvider(currentUserService.getRequiredUserId(), id));
  }

  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> setDefaultChatProvider(@RequestBody UserDefaultRequest request) {
    userLlmConfigService.setDefaultChatProvider(currentUserService.getRequiredUserId(), request);
    return Result.success();
  }

  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> setDefaultEmbeddingProvider(@RequestBody UserDefaultRequest request) {
    userLlmConfigService.setDefaultEmbeddingProvider(currentUserService.getRequiredUserId(), request);
    return Result.success();
  }

  // ===== 语音服务（ASR / TTS） =====

  @GetMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<AsrConfigDTO> getMyAsrConfig() {
    return Result.success(
        userLlmConfigService.getMyAsrConfig(currentUserService.getRequiredUserId()));
  }

  @PutMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> saveMyAsrConfig(@RequestBody AsrConfigRequest request) {
    userLlmConfigService.saveMyAsrConfig(currentUserService.getRequiredUserId(), request);
    return Result.success();
  }

  @GetMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<TtsConfigDTO> getMyTtsConfig() {
    return Result.success(
        userLlmConfigService.getMyTtsConfig(currentUserService.getRequiredUserId()));
  }

  @PutMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<Void> saveMyTtsConfig(@RequestBody TtsConfigRequest request) {
    userLlmConfigService.saveMyTtsConfig(currentUserService.getRequiredUserId(), request);
    return Result.success();
  }

  @PostMapping("/voice/asr/test")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 10)
  public Result<ProviderTestResult> testMyAsrConfig() {
    return Result.success(
        userLlmConfigService.testMyAsrConfig(currentUserService.getRequiredUserId()));
  }
}
