package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通用文档解析服务，供知识库与简历模块复用。
 *
 * <p>边界约束（DOC-001）：
 * <ul>
 *   <li>单文档字节上限 {@link #MAX_DOCUMENT_BYTES}，超过直接拒绝，不进入解析器；</li>
 *   <li>解析正文上限 {@link #MAX_TEXT_LENGTH}（字符），由 BodyContentHandler 截断；</li>
 *   <li>清洗后文本上限 {@link #MAX_CLEANED_TEXT_LENGTH}，超出部分丢弃并记录告警；</li>
 *   <li>解析超时 {@link #PARSE_TIMEOUT_SECONDS}，超时中断工作线程并返回明确业务错误；</li>
 *   <li>解析在独立的固定线程池中执行，避免恶意文档占满 Web 线程。</li>
 * </ul>
 *
 * <p>错误处理：对外只返回用户可理解的固定文案，解析器原始异常仅写入日志，
 * 避免把文件内部结构、临时路径等信息回显给调用方。
 */
@Slf4j
@Service
public class DocumentParseService {

    /** 单文档字节上限 50MB，与上传入口限制保持一致。 */
    static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;

    /** 解析正文上限（字符）。 */
    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024;

    /** 清洗后进入下游（分块 / LLM）的文本上限（字符）。 */
    private static final int MAX_CLEANED_TEXT_LENGTH = 2 * 1024 * 1024;

    /** 单次解析超时时间。 */
    static final long PARSE_TIMEOUT_SECONDS = 30L;

    private static final int PARSE_WORKER_THREADS = 2;
    private static final int PARSE_QUEUE_CAPACITY = 16;

    private static final String PARSE_FAILED_MESSAGE = "文件解析失败，请确认文件未加密、未损坏且格式受支持";

    /**
     * 解析线程池：显式参数，禁用无界队列与默认线程工厂，避免被恶意文档打爆。
     */
    private static final ThreadPoolExecutor PARSE_EXECUTOR = new ThreadPoolExecutor(
        PARSE_WORKER_THREADS,
        PARSE_WORKER_THREADS,
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(PARSE_QUEUE_CAPACITY),
        runnable -> {
            Thread thread = new Thread(runnable, "document-parse");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());

    private final TextCleaningService textCleaningService;

    public DocumentParseService(TextCleaningService textCleaningService) {
        this.textCleaningService = textCleaningService;
    }

    @PreDestroy
    void shutdownExecutor() {
        PARSE_EXECUTOR.shutdownNow();
    }

    /**
     * 解析上传的文件，提取文本内容。
     *
     * @param file 上传的文件（PDF/DOCX/DOC/TXT/MD 等）
     * @return 提取并清洗后的文本
     */
    public String parseContent(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return "";
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            log.warn("文件超过解析上限: fileName={}, size={}", fileName, file.getSize());
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "文件大小超过解析上限（" + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB）");
        }

        try (InputStream inputStream = file.getInputStream()) {
            return parseBytes(inputStream.readAllBytes(), fileName);
        } catch (IOException e) {
            log.error("读取上传文件失败: fileName={}", fileName, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件解析失败，无法读取上传内容，请重新上传");
        }
    }

    /**
     * 解析字节数组形式的文件内容。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（仅用于日志）
     * @return 提取并清洗后的文本
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（从字节数组）: {}", fileName);
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节数组为空: {}", fileName);
            return "";
        }
        return parseBytes(fileBytes, fileName);
    }

    private String parseBytes(byte[] fileBytes, String fileName) {
        if (fileBytes.length > MAX_DOCUMENT_BYTES) {
            log.warn("文件超过解析上限: fileName={}, size={}", fileName, fileBytes.length);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "文件大小超过解析上限（" + (MAX_DOCUMENT_BYTES / 1024 / 1024) + "MB）");
        }

        String content = parseWithTimeout(fileBytes, fileName);
        String cleanedContent = textCleaningService.cleanText(content);
        if (cleanedContent.length() > MAX_CLEANED_TEXT_LENGTH) {
            log.warn("解析文本超过下游上限，已截断: fileName={}, length={}, limit={}",
                fileName, cleanedContent.length(), MAX_CLEANED_TEXT_LENGTH);
            cleanedContent = cleanedContent.substring(0, MAX_CLEANED_TEXT_LENGTH);
        }
        log.info("文件解析成功: fileName={}, 文本长度={}", fileName, cleanedContent.length());
        return cleanedContent;
    }

    private String parseWithTimeout(byte[] fileBytes, String fileName) {
        Future<String> future;
        try {
            future = PARSE_EXECUTOR.submit(() -> parseContent(new ByteArrayInputStream(fileBytes)));
        } catch (RejectedExecutionException e) {
            log.warn("解析线程池已满，拒绝解析请求: fileName={}", fileName);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文档解析繁忙，请稍后重试");
        }

        try {
            return future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("文档解析超时: fileName={}, timeout={}s", fileName, PARSE_TIMEOUT_SECONDS);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "文件解析超时，请压缩文件后重试");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("文档解析被中断: fileName={}", fileName);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析被中断，请重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("文件解析失败: fileName={}", fileName, cause);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, PARSE_FAILED_MESSAGE);
        }
    }

    /**
     * 核心解析：显式 Parser + ParseContext，禁用嵌入资源与图片提取。
     */
    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        parser.parse(inputStream, handler, metadata, context);
        return handler.toString();
    }

    /**
     * 从对象存储下载并解析内容。
     */
    public String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseContent(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}", storageKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败，请稍后重试");
        }
    }

    /**
     * 暴露给测试的文本上限，避免测试硬编码魔数。
     */
    static int maxCleanedTextLength() {
        return MAX_CLEANED_TEXT_LENGTH;
    }
}
