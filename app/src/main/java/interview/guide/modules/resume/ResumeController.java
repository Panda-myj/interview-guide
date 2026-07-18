package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 简历控制器
 * Resume Controller for upload and analysis
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "简历管理", description = "简历上传、分析、导出与删除")
//@Tag 提供了文档分组。
//接口分组/分类。将相关接口归入同一逻辑模块，在 UI 上折叠显示
public class ResumeController {

    private final ResumeUploadService uploadService;
    private final ResumeDeleteService deleteService;
    private final ResumeHistoryService historyService;

    /**
     * 上传简历并获取分析结果
     *
     * @param file 简历文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 简历分析结果，包含评分和建议
     *
     * ⚠️ 反面教材：返回 Map 丢失了类型安全，重构时应改为强类型 DTO
     */
    // consumes 是 Spring MVC 中用于声明该接口只接受特定 Content-Type 请求体的过滤条件。
//    它的核心作用不是“告诉前端怎么传参”，而是在服务端做请求匹配与拦截
    //consumes 不是文档注解，而是运行时过滤器
    /*@PostMapping(
    value = "/api/resumes/upload",
    consumes = MediaType.MULTIPART_FORM_DATA_VALUE,  // 只接收 multipart
    produces = MediaType.APPLICATION_JSON_VALUE       // 只返回 JSON
)*/
    @PostMapping(value = "/api/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = uploadService.uploadAndAnalyze(file);
        boolean isDuplicate = (Boolean) result.get("duplicate");
        if (isDuplicate) {
            return Result.success("检测到相同简历，已返回历史分析结果", result);
        }
        return Result.success(result);
    }

    /**
     * 获取所有简历列表
     */
    @GetMapping("/api/resumes")
    public Result<List<ResumeListItemDTO>> getAllResumes() {
        List<ResumeListItemDTO> resumes = historyService.getAllResumes();
        return Result.success(resumes);
    }

    /**
     * 获取简历详情（包含分析历史）
     */
    @GetMapping("/api/resumes/{id}/detail")
    public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long id) {
        ResumeDetailDTO detail = historyService.getResumeDetail(id);
        return Result.success(detail);
    }

    /**
     * 导出简历分析报告为PDF
     */
//    思考题：为什么导出不能用 Result？（答案：因为浏览器需要识别二进制流和特定的 Header，JSON 包装会破坏文件下载）
    @GetMapping("/api/resumes/{id}/export")
    public ResponseEntity<byte[]> exportAnalysisPdf(@PathVariable Long id) {
        try {
            var result = historyService.exportAnalysisPdf(id);
            String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
//                    Header 无法通过 JSON 表达，文件下载依赖一个特殊的响应头：CONTENT_DISPOSITION，这个头告诉浏览器：“这是一个附件，文件名叫做‘简历分析.pdf’”。
                .contentType(MediaType.APPLICATION_PDF)
                    //当浏览器收到一个 HTTP 响应时，它首先看 Content-Type 头：
//            如果是 application/json → 浏览器认为这是数据，交给 JS 解析或直接在页面展示。
//            如果是 application/pdf → 浏览器认为这是文件，触发内置 PDF 预览器或弹出下载框。
//            如果是 application/octet-stream → 浏览器不认识，直接弹出下载框。
                .body(result.pdfBytes());
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除简历
     *
     * @param id 简历ID
     * @return 删除结果
     */
    @DeleteMapping("/api/resumes/{id}")
    public Result<Void> deleteResume(@PathVariable Long id) {
        deleteService.deleteResume(id);
        return Result.success(null);
    }

    /**
     * 重新分析简历（手动重试）
     * 用于分析失败后的重试     用了 POST 而不是 GET，因为它是有副作用的操作。
     *
     * @param id 简历ID
     * @return 结果
     */
    @PostMapping("/api/resumes/{id}/reanalyze")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> reanalyze(@PathVariable Long id) {
        uploadService.reanalyze(id);
        return Result.success(null);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/api/resumes/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of(
            "status", "UP",
            "service", "AI Interview Platform - Resume Service"
        ));
    }

}
