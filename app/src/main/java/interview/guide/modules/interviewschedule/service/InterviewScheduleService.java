package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.auth.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;
    private final CurrentUserService currentUserService;

    /** 列表接口默认返回条数。 */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** 列表接口单次返回的硬上限，防止全量拉取（PERF-002）。 */
    private static final int MAX_PAGE_SIZE = 200;

    private static final String[] COPYABLE_FIELDS = {
        "companyName", "position", "interviewTime", "interviewType",
        "meetingLink", "roundNumber", "interviewer", "notes"
    };

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        entity.setOwnerUser(currentUserService.getRequiredUser());
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        // 先按属主校验存在性：deleteById 不区分属主，会误删其他用户的日程
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        repository.delete(entity);
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    /**
     * 获取面试记录列表（截断式分页：默认 50 条、上限 200 条）。
     *
     * <p>保持返回 List 形状（截断而非改契约）；后续如需翻页/总数，
     * 应演进为 Page 契约并同步前端。排序在数据库层完成（PERF-002）。</p>
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end, int size) {
        Long ownerUserId = currentUserService.getRequiredUserId();
        PageRequest pageRequest = PageRequest.of(0, Math.max(1, Math.min(size, MAX_PAGE_SIZE)));

        List<InterviewScheduleEntity> entities;
        if (start != null && end != null) {
            entities = repository.findByOwnerUserIdAndInterviewTimeBetween(
                ownerUserId, start, end, pageRequest.withSort(Sort.by(Sort.Direction.ASC, "interviewTime")));
        } else if (status != null) {
            entities = repository.findByOwnerUserIdAndStatus(
                ownerUserId, InterviewStatus.valueOf(status), pageRequest.withSort(
                    Sort.by(Sort.Direction.ASC, "interviewTime")));
        } else {
            entities = repository.findAllByOwnerUserIdOrderByInterviewTimeAsc(ownerUserId, pageRequest);
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    /**
     * 获取面试记录列表（保持向后兼容，默认 50 条）
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        return getAll(status, start, end, DEFAULT_PAGE_SIZE);
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return repository.findByIdAndOwnerUserId(id, currentUserService.getRequiredUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
