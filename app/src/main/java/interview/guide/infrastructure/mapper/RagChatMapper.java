package interview.guide.infrastructure.mapper;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.MessageDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import org.mapstruct.*;

import java.util.List;

/**
 * RAG聊天相关实体到DTO的映射器
 * 使用MapStruct自动生成转换代码
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    uses = KnowledgeBaseMapper.class
)
public interface RagChatMapper {
    
    /**
     * 将会话实体转换为会话DTO
     */
    @Mapping(target = "knowledgeBaseIds", source = "session", qualifiedByName = "extractKnowledgeBaseIds")
    SessionDTO toSessionDTO(RagChatSessionEntity session);
    
    /**
     * 将消息实体转换为消息DTO
     */
    @Mapping(target = "type", source = "message", qualifiedByName = "getTypeString")
    MessageDTO toMessageDTO(RagChatMessageEntity message);
    
    /**
     * 将消息实体列表转换为消息DTO列表
     */
    List<MessageDTO> toMessageDTOList(List<RagChatMessageEntity> messages);

    /**
     * 从会话实体中提取知识库ID列表
     */
    @Named("extractKnowledgeBaseIds")
    default List<Long> extractKnowledgeBaseIds(RagChatSessionEntity session) {
        return session.getKnowledgeBaseIds();
    }
    
    /**
     * 获取消息类型字符串
     */
    @Named("getTypeString")
    default String getTypeString(RagChatMessageEntity message) {
        return message.getTypeString();
    }
    
    /**
     * 将会话实体转换为会话列表项DTO（批量填充知识库名称版本，PERF-002）。
     * 知识库名称由 Service 层一次批量查询后传入，
     * 避免逐会话懒加载 @ManyToMany knowledgeBases 触发 N+1 查询。
     */
    default SessionListItemDTO toSessionListItemDTO(
            RagChatSessionEntity session,
            List<String> knowledgeBaseNames) {
        return new SessionListItemDTO(
            session.getId(),
            session.getTitle(),
            session.getMessageCount(),
            knowledgeBaseNames,
            session.getUpdatedAt(),
            getIsPinnedWithDefault(session)
        );
    }
    
    /**
     * 处理isPinned的null值，默认为false
     */
    @Named("getIsPinnedWithDefault")
    default Boolean getIsPinnedWithDefault(RagChatSessionEntity session) {
        return session.getIsPinned() != null ? session.getIsPinned() : false;
    }
    
    /**
     * 将会话实体和消息列表转换为会话详情DTO
     * 注意：这个方法需要手动实现，因为需要组合多个数据源
     * 知识库列表的转换在Service层使用KnowledgeBaseMapper完成
     */
    default SessionDetailDTO toSessionDetailDTO(
            RagChatSessionEntity session, 
            List<RagChatMessageEntity> messages,
            List<KnowledgeBaseListItemDTO> knowledgeBases) {
        List<MessageDTO> messageDTOs = toMessageDTOList(messages);
        
        return new SessionDetailDTO(
            session.getId(),
            session.getTitle(),
            knowledgeBases,
            messageDTOs,
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}

