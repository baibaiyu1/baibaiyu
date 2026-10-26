package com.gmd.faq.server.main.common.config.auth;

import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.TenantRole;
import com.gmd.faq.server.main.common.util.*;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：权限认证拦截器
 *
 **/
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private static final int UNAUTHORIZED_STATUS = HttpServletResponse.SC_UNAUTHORIZED;
    private static final int OK_STATUS = HttpServletResponse.SC_OK;

    /** 存储有效的SK ID集合，用于快速校验SK是否有效 */
    private static final Set<Long> SK_ID_SET = ConcurrentHashMap.newKeySet();

    private final RedisUtils redisUtils;
    private final PermsResolver permsResolver;
    private final JdbcTemplate jdbcTemplate;

    public AuthInterceptor(RedisUtils redisUtils, PermsResolver permsResolver, JdbcTemplate jdbcTemplate) {
        this.redisUtils = redisUtils;
        this.permsResolver = permsResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 添加有效的SK ID到集合中（创建SK时调用）
     *
     * @param skId SK的ID
     */
    public static void addSkId(Long skId) {
        SK_ID_SET.add(skId);
    }

    /**
     * 批量移除SK ID（批量删除SK时调用）
     *
     * @param skIds SK的ID列表
     */
    public static void removeSkIds(List<Long> skIds) {
        skIds.forEach(SK_ID_SET::remove);
    }

    /**
     * 检查SK ID是否有效
     *
     * @param skId SK的ID
     * @return 是否有效
     */
    public static boolean isSkIdValid(Long skId) {
        return SK_ID_SET.contains(skId);
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        RequestHolder.clear();
        TenantUtils.clear();
        RequestHolder.setRequest(request);

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查是否需要跳过认证
        if (shouldSkipAuthentication(handlerMethod)) {
            log.debug("跳过认证: {}", handlerMethod.getMethod().getName());
            return true;
        }

        boolean accepted = false;
        try {
            String token = TokenUtils.getToken();
            if (token == null) {
                sendErrorResponse(response, OK_STATUS, UNAUTHORIZED_STATUS, "无权进行此操作");
                return false;
            }

            // SK token 走独立的认证分支
            if (token.startsWith("sk-")) {
                accepted = handleSkToken(token, request, response, handlerMethod);
                return accepted;
            }

            // 常规 JWT token 认证
            accepted = handleJwtToken(token, request, response, handlerMethod);
            return accepted;

        } catch (Exception e) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, e.getMessage());
            return false;
        } finally {
            if (!accepted) {
                RequestHolder.clear();
                TenantUtils.clear();
            }
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        RequestHolder.clear();
        TenantUtils.clear();
    }

    /**
     * 检查是否需要跳过认证
     */
    private boolean shouldSkipAuthentication(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        Class<?> controllerClass = handlerMethod.getBeanType();

        return method.isAnnotationPresent(PassAuth.class) ||
                controllerClass.isAnnotationPresent(PassAuth.class);
    }

    /**
     * 处理JWT Token认证
     *
     * @param token         JWT token
     * @param request       HTTP请求
     * @param response      HTTP响应
     * @param handlerMethod 处理方法
     * @return 是否通过认证
     */
    private boolean handleJwtToken(String token,
                                   HttpServletRequest request,
                                   HttpServletResponse response,
                                   HandlerMethod handlerMethod) {
        Claims claims = TokenUtils.parseAndValidateToken(token);

        // 检查用户登录状态
        if (redisUtils.get(RedisKeyBuilder.globalKey("login:" + token)) == null) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "用户未登录");
            return false;
        }

        UserDetail userDetail = JsonUtils.parse(claims.getSubject(), UserDetail.class);
        if (userDetail == null) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "Token用户信息无效");
            return false;
        }
        boolean visitor = UserUtils.AUTH_CHAT_KEY.equals(userDetail.getAuthType());
        if (visitor) {
            if (!validateChatKeyAccess(claims, userDetail, handlerMethod, response)) {
                return false;
            }
            userDetail.setChatKeyId(claims.getId());
            userDetail.setPerms(List.of());
            userDetail.setDataScope("SELF");
        } else {
            // Older visitor tokens had no auth type and used a nonnumeric share key as JWT id.
            if (claims.getId() == null || !claims.getId().matches("[0-9]+")) {
                sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "Token身份无效");
                return false;
            }
            userDetail.setAuthType(UserUtils.AUTH_ACCOUNT);
        }
        request.setAttribute(SysConst.USER_DETAIL, userDetail);

        // 设置租户上下文
        if (userDetail.getTenantId() != null) {
            TenantUtils.setCurrentTenant(userDetail.getTenantId(), userDetail.getTenantCode());
        }

        // 解析当前租户下的权限点与数据范围（单轨权限 + 数据权限：必须在设置租户上下文之后，租户拦截器才会补 tenant_id 条件）
        if (!visitor) {
            PermsResolver.AccessContext access = permsResolver.resolveAccess(userDetail.getId(), userDetail.getTenantId());
            userDetail.setPerms(access.perms());
            userDetail.setDataScope(access.dataScope().name());
            // 部门（组织）ID 以库中实时值为准：JWT 里的 deptId 可能在管理员调整归属后已过期。
            userDetail.setDeptId(access.deptId());
        }

        // 检查接口权限
        if (!checkPermNeed(handlerMethod, userDetail)) {
            sendErrorResponse(response, OK_STATUS, UNAUTHORIZED_STATUS, "无权进行此操作");
            return false;
        }

        return true;
    }

    /**
     * 验证ChatKey访问权限
     *
     * @param claims        JWT claims
     * @param handlerMethod 处理方法
     * @param response      HTTP响应
     * @return 是否允许访问
     */
    private boolean validateChatKeyAccess(Claims claims, UserDetail userDetail,
                                          HandlerMethod handlerMethod, HttpServletResponse response) {
        if (!isChatKeyAccessAllowed(handlerMethod)) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "该接口不支持ChatKey访问");
            return false;
        }
        String chatKey = claims.getId();
        if (chatKey == null || userDetail.getBoundAgentId() == null
                || userDetail.getBoundAgentCode() == null || userDetail.getTenantId() == null) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "分享身份无效");
            return false;
        }
        String agentCode = redisUtils.get(RedisKeyBuilder.globalKey("chatkey:" + chatKey));
        if (!userDetail.getBoundAgentCode().equals(agentCode)) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "分享链接已失效");
            return false;
        }
        String sql = "SELECT a.id FROM " + TableConst.AGENT_CHAT_KEY + " k JOIN " + TableConst.AGENT
                + " a ON a.agent_code = k.agent_code AND a.tenant_id = k.tenant_id"
                + " WHERE k.chat_key = ? AND k.tenant_id = ? AND a.id = ? AND a.agent_code = ?"
                + " AND a.enabled = 1 AND a.published = 1 AND a.external_share_enabled = 1";
        boolean active = !jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1),
                chatKey, userDetail.getTenantId(), userDetail.getBoundAgentId(),
                userDetail.getBoundAgentCode()).isEmpty();
        if (!active) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "分享链接已失效");
        }
        return active;
    }

    /**
     * 处理SK token认证：验证token有效性并检查接口是否允许SK访问
     *
     * @param token         以sk-开头的完整token
     * @param request       HTTP请求
     * @param response      HTTP响应
     * @param handlerMethod 处理方法
     * @return 是否通过认证
     */
    private boolean handleSkToken(String token,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  HandlerMethod handlerMethod) {
        // 检查接口是否被 @SkAccess 标记
        if (!isSkAccessAllowed(handlerMethod)) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "该接口不支持SK访问");
            return false;
        }

        try {
            // 剔除 sk- 前缀，解压还原原始JWT后解析
            String jwtToken = TokenUtils.decompressJwt(token.substring(3));
            Claims claims = TokenUtils.parseAndValidateToken(jwtToken);

            // subject 即为创建SK时传入的name（JsonUtils.toJsonStr对String直接返回原值）
            Long id = Long.parseLong(claims.getId());

            // 校验SK ID是否有效（是否被删除）
            if (!isSkIdValid(id)) {
                sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "SK已失效");
                return false;
            }

            // 将用户信息存储到请求中供后续使用
            UserDetail userDetail = JsonUtils.parse(claims.getSubject(), UserDetail.class);
            if (userDetail == null || userDetail.getTenantId() == null) {
                sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "SK身份无效");
                return false;
            }
            String sql = "SELECT s.created_by, a.dept_id FROM " + TableConst.SECRET_KEY + " s JOIN "
                    + TableConst.ACCOUNT + " a ON a.id = s.created_by"
                    + " WHERE s.id = ? AND s.tenant_id = ? AND s.enabled = 1"
                    + " AND (s.expire_time IS NULL OR s.expire_time > NOW())";
            List<java.util.Map<String, Object>> owners = jdbcTemplate.queryForList(sql, id, userDetail.getTenantId());
            if (owners.isEmpty() || owners.getFirst().get("created_by") == null) {
                sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, "SK已失效");
                return false;
            }
            userDetail.setPrincipalAccountId(((Number) owners.getFirst().get("created_by")).longValue());
            Object deptId = owners.getFirst().get("dept_id");
            userDetail.setDeptId(deptId == null ? null : ((Number) deptId).longValue());
            userDetail.setAuthType(UserUtils.AUTH_SK);
            userDetail.setSkId(id);
            userDetail.setPerms(List.of());
            request.setAttribute(SysConst.USER_DETAIL, userDetail);

            // 设置租户上下文
            if (userDetail.getTenantId() != null) {
                TenantUtils.setCurrentTenant(userDetail.getTenantId(), userDetail.getTenantCode());
            }

            return true;

        } catch (Exception e) {
            sendErrorResponse(response, UNAUTHORIZED_STATUS, UNAUTHORIZED_STATUS, e.getMessage());
            return false;
        }
    }

    /**
     * 检查方法或类是否被 @SkAccess 标记
     *
     * @param handlerMethod 处理方法
     * @return 是否允许SK访问
     */
    private boolean isSkAccessAllowed(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        Class<?> controllerClass = handlerMethod.getBeanType();
        return method.isAnnotationPresent(SkAccess.class) ||
                controllerClass.isAnnotationPresent(SkAccess.class);
    }

    /**
     * 检查方法或类是否被 @ChatKeyAccess 标记
     *
     * @param handlerMethod 处理方法
     * @return 是否允许ChatKey访问
     */
    private boolean isChatKeyAccessAllowed(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        Class<?> controllerClass = handlerMethod.getBeanType();
        return method.isAnnotationPresent(ChatKeyAccess.class) ||
                controllerClass.isAnnotationPresent(ChatKeyAccess.class);
    }

    /**
     * 检查接口权限（单轨权限）
     * <p>
     * 只认「角色 → 菜单/按钮 perms」：命中 {@link PermNeed#value()} 中任意一个即通过。
     * 方法上的注解优先于类上的注解；两处都没有则视为登录即可访问。
     * <p>
     * 说明：本方法刻意**不**回退到 {@code account_tenant.role} 旧枚举——
     * 旧枚举已在 V13 迁移中一次性映射为内置角色（editor/viewer），账号的权限一律以 perms 为准。
     */
    private boolean checkPermNeed(HandlerMethod handlerMethod, UserDetail userDetail) {
        Method method = handlerMethod.getMethod();

        PermNeed annotation = method.getAnnotation(PermNeed.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(PermNeed.class);
        }
        if (annotation == null || annotation.value().length == 0) {
            return true;
        }

        List<String> perms = userDetail.getPerms();
        if (perms == null || perms.isEmpty()) {
            return false;
        }

        for (String required : annotation.value()) {
            if (perms.contains(required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发送错误响应
     *
     * @param response   HTTP响应
     * @param httpStatus HTTP状态码
     * @param code       业务错误码
     * @param message    错误消息
     */
    private void sendErrorResponse(HttpServletResponse response, int httpStatus, int code, String message) {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");

        try {
            String errorJson = String.format("{\"code\": %d, \"msg\": \"%s\"}", code, message);
            response.getWriter().write(errorJson);
        } catch (Exception e) {
            log.error("写入错误响应失败", e);
        }
    }
}
