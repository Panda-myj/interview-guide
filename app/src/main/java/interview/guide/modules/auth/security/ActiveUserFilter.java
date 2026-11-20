package interview.guide.modules.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            // 角色降权后旧 access token 内仍是旧 role，必须一并校验，否则最长 15 分钟内
            // 仍能凭 ADMIN claim 访问管理接口。
            boolean stillValid = userRepository.findById(principal.userId())
                .map(user -> user.getStatus() == UserStatus.ACTIVE
                    && user.getRole() == principal.role())
                .orElse(false);
            if (!stillValid) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(
                    response.getWriter(),
                    Result.error(ErrorCode.AUTH_USER_DISABLED, "账号已被禁用、不存在或权限已变更，请重新登录")
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
