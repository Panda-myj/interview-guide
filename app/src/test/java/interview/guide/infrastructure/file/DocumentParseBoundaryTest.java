package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-001 边界测试：大小上限、文本截断、错误文案不泄露内部细节。
 */
@DisplayName("文档解析边界与安全")
class DocumentParseBoundaryTest {

    private DocumentParseService service;

    @BeforeEach
    void setUp() {
        TextCleaningService cleaningService = new TextCleaningService();
        service = new DocumentParseService(cleaningService);
    }

    @Nested
    @DisplayName("大小与截断边界")
    class SizeBoundaries {

        @Test
        @DisplayName("超过字节上限的上传文件在解析前被拒绝")
        void rejectsOversizedUploadBeforeParsing() {
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getSize()).thenReturn(DocumentParseService.MAX_DOCUMENT_BYTES + 1);

            assertThatThrownBy(() -> service.parseContent(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件大小超过解析上限");
        }

        @Test
        @DisplayName("超长文本在清洗后按下游上限截断")
        void truncatesCleanedTextToDownstreamLimit() {
            int limit = DocumentParseService.maxCleanedTextLength();
            StringBuilder builder = new StringBuilder(limit + 50_000);
            while (builder.length() < limit + 50_000) {
                builder.append("Java 集合框架与并发编程知识点。\n");
            }
            byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);

            String result = service.parseContent(bytes, "huge.txt");

            assertThat(result.length()).isEqualTo(limit);
        }

        @Test
        @DisplayName("空字节数组直接返回空串")
        void emptyBytesReturnEmptyString() {
            assertThat(service.parseContent(new byte[0], "empty.txt")).isEmpty();
        }
    }

    @Nested
    @DisplayName("错误文案")
    class ErrorMessages {

        @Test
        @DisplayName("损坏文档返回固定文案且不泄露解析器内部细节")
        void corruptDocumentReturnsGenericMessage() {
            byte[] corrupt = "%PDF-1.7 这不是一个合法 PDF".getBytes(StandardCharsets.UTF_8);
            MockMultipartFile file = new MockMultipartFile(
                "file", "corrupt.pdf", "application/pdf", corrupt);

            assertThatThrownBy(() -> service.parseContent(file))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("org.apache.tika")
                .hasMessageNotContaining("Exception")
                .hasMessageNotContaining("at interview.guide");
        }
    }
}
