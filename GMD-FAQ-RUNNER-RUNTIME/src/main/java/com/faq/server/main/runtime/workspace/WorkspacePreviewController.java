package com.gmd.faq.server.main.runtime.workspace;

import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.config.auth.AuthInterceptor;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.config.auth.PassAuth;
import com.gmd.faq.server.main.common.config.auth.PermsResolver;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.RedisKeyBuilder;
import com.gmd.faq.server.main.common.util.RedisUtils;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.TokenUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 描述：工作空间文件「内联预览」端点
 *
 * <p>存在的唯一原因：**iframe 无法携带 Authorization 请求头**。
 * 本仓鉴权是 Bearer 头（见 utils/request.ts），因此 &lt;iframe src&gt; 天然拿不到身份，
 * 无法直接预览工作空间里的 HTML。这里用一枚短时效、绑定会话的令牌来解决。
 *
 * <p>令牌放在**路径**而不是查询串里，是为了让页面内的相对资源引用
 * （./style.css、./img/x.png）解析后仍然带着令牌 —— 放查询串的话
 * 相对路径解析会把查询串丢掉，子资源就会 401。这是整个设计的关键点。
 *
 **/
@Slf4j
@RestController
@RequestMapping("/runtime/workspace")
@RequiredArgsConstructor
public class WorkspacePreviewController {

    private final SessionAccessGuard sessionAccessGuard;
    private final PermsResolver permsResolver;
    private final RedisUtils redisUtils;
    private final JdbcTemplate jdbcTemplate;

    /** 预览令牌有效期：只够把页面打开，过期后需重新获取 */
    private static final long PREVIEW_TOKEN_TTL_MS = 5 * 60 * 1000L;

    /**
     * 允许**内联**返回的扩展名 → Content-Type。
     *
     * <p>白名单之外的扩展名一律走 attachment 下载，绝不内联 ——
     * 否则一个 .sh/.exe 被内联返回就可能被浏览器当可执行内容处理。
     */
    private static final Map<String, String> INLINE_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/plain; charset=utf-8"),
            Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg")
    );

    /** 预览令牌承载的身份信息 */
    public record PreviewTokenPayload(String tenantCode, Long tenantId, Long userId,
                                      String authType, Long boundAgentId, String boundAgentCode,
                                      String chatKeyId, Long skId) {
    }

    /**
     * 签发预览令牌
     *
     * <p>走常规鉴权（未标 @PassAuth），因此这里能拿到请求级租户上下文。
     * 令牌绑定 sessionId 与租户，5 分钟过期。
     *
     * @param sessionId 会话 ID
     * @return 预览令牌
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/preview-token")
    public R<String> previewToken(@RequestParam("sessionId") String sessionId) {
        validateSessionId(sessionId);
        sessionAccessGuard.requireRead(Long.valueOf(sessionId));

        String tenantCode = TenantUtils.getCurrentTenantCode();
        if (tenantCode == null) {
            throw new BusinessException("无法确定当前租户");
        }

        PreviewTokenPayload payload = new PreviewTokenPayload(
                tenantCode, TenantUtils.getCurrentTenantId(), UserUtils.getId(),
                UserUtils.getUserDetail().getAuthType(), UserUtils.getBoundAgentId(),
                UserUtils.getBoundAgentCode(), UserUtils.getChatKeyId(), UserUtils.getSkId());
        // id 传 sessionId：校验时据此确认令牌没有被挪到别的会话上使用
        String token = TokenUtils.createToken(sessionId, payload, PREVIEW_TOKEN_TTL_MS);
        return R.data(token);
    }

    /**
     * 内联返回工作空间文件（供 iframe 直接导航）
     *
     * <p>标 @PassAuth 跳过常规鉴权，改为在此校验预览令牌 —— 因为 iframe 请求带不了请求头。
     *
     * @param token     预览令牌
     * @param sessionId 会话 ID（必须与令牌绑定的会话一致）
     * @param path      工作空间内相对路径
     */
    @PassAuth
    @GetMapping("/raw/{token}/{sessionId}/{*path}")
    public void raw(@PathVariable("token") String token,
                    @PathVariable("sessionId") String sessionId,
                    @PathVariable("path") String path,
                    HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        // 这里刻意**不抛异常**：全局异常处理器会把错误包成 200 + JSON 信封，
        // 而 iframe 导航需要的是真实状态码；且 response.sendError 会触发 ERROR 转发，
        // /error 再走一遍拦截器 → 404 会被改写成 401「未提供认证Token」。
        try {
            validateSessionId(sessionId);
            PreviewTokenPayload payload = verifyToken(token, sessionId);
            checkLiveAccess(payload, Long.valueOf(sessionId), request);

            // 去掉 {*path} 捕获到的前导斜杠
            String relativePath = path == null ? "" : path.replaceFirst("^/+", "");
            Path file = resolveInsideWorkspace(payload.tenantCode(), sessionId, relativePath);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                writeError(response, HttpServletResponse.SC_NOT_FOUND, "文件不存在");
                return;
            }

            writeFile(response, file);
        } catch (BusinessException e) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "无效的会话ID");
        }
    }

    /**
     * 以适当的 Content-Type 输出文件
     */
    private void writeFile(HttpServletResponse response, Path file) throws IOException {
        String extension = extensionOf(file.getFileName().toString());
        String inlineType = INLINE_TYPES.get(extension);

        // 安全响应头：
        //   nosniff  —— 禁止浏览器按内容嗅探类型（防把文本当脚本执行）
        //   no-referrer —— 令牌在 URL 路径里，绝不能让 Referer 把它带给第三方
        //   CSP sandbox —— 与 iframe 的 sandbox 属性取交集，双重保险；
        //                  不设 default-src，故子资源加载不受影响
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts allow-forms allow-modals");
        // 预览页是不透明源，取同目录下的 json 数据属于跨源读取，需放行
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        if (inlineType == null) {
            // 白名单之外一律下载，绝不内联
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8));
        } else {
            response.setContentType(inlineType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        }

        response.setContentLengthLong(Files.size(file));
        try (OutputStream output = response.getOutputStream()) {
            Files.copy(file, output);
        }
    }

    /**
     * 直接写回错误状态与纯文本正文
     *
     * <p>不能用 sendError：那会触发 ERROR 转发，而本项目对 /error 也走鉴权拦截器，
     * 于是 404/403 统统被改写成 401「未提供认证Token」——对 iframe 预览毫无意义。
     */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write(message == null ? "预览失败" : message);
    }

    /**
     * 校验预览令牌，并确认它确实是发给这个会话的
     *
     * @param token     预览令牌
     * @param sessionId 路径上的会话 ID
     * @return 令牌载荷
     */
    private PreviewTokenPayload verifyToken(String token, String sessionId) {
        Claims claims;
        try {
            claims = TokenUtils.parseToken(token);
        } catch (Exception e) {
            throw new BusinessException("预览令牌无效或已过期");
        }

        // 令牌绑定会话：换一个 sessionId 复用同一枚令牌必须被拒
        if (!sessionId.equals(claims.getId())) {
            throw new BusinessException("预览令牌与目标会话不匹配");
        }

        try {
            PreviewTokenPayload payload = JsonUtils.parse(claims.getSubject(), PreviewTokenPayload.class);
            if (payload == null || payload.tenantCode() == null || payload.tenantId() == null
                    || payload.userId() == null) {
                throw new BusinessException("预览令牌内容不完整");
            }
            return payload;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("预览令牌内容无法解析");
        }
    }

    /**
     * 解析工作空间内的文件路径
     *
     * <p>本端点标了 @PassAuth，**没有请求级租户上下文**，因此租户目录必须由令牌里的
     * tenantCode 拼出，不能依赖 SysConst.getWorkspacePath() 的无参重载。
     *
     * @param tenantCode   令牌中的租户编号
     * @param sessionId    会话 ID
     * @param relativePath 相对路径
     * @return 规范化后的绝对路径
     */
    private Path resolveInsideWorkspace(String tenantCode, String sessionId, String relativePath) {
        if (relativePath.isBlank()) {
            throw new BusinessException("文件路径不能为空");
        }
        // 与 WorkspaceServiceImpl.resolveAndValidate 同一口径：先规范化再确认没越出工作空间
        Path workspaceDir = Paths.get(SysConst.getWorkspacePath(tenantCode), sessionId)
                .toAbsolutePath().normalize();
        Path resolved = workspaceDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceDir)) {
            throw new BusinessException("非法的文件路径，不允许越界访问");
        }
        Path current = workspaceDir;
        if (Files.isSymbolicLink(current)) {
            throw new BusinessException("工作空间不允许访问符号链接");
        }
        for (Path part : workspaceDir.relativize(resolved)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new BusinessException("工作空间不允许访问符号链接");
            }
        }
        return resolved;
    }

    private void checkLiveAccess(PreviewTokenPayload payload, Long sessionId, HttpServletRequest request) {
        if (!UserUtils.AUTH_ACCOUNT.equals(payload.authType())
                && !UserUtils.AUTH_CHAT_KEY.equals(payload.authType())
                && !UserUtils.AUTH_SK.equals(payload.authType())) {
            throw new BusinessException("预览令牌身份无效");
        }
        TenantUtils.setCurrentTenant(payload.tenantId(), payload.tenantCode());
        UserDetail detail = UserDetail.builder()
                .id(payload.userId())
                .tenantId(payload.tenantId())
                .tenantCode(payload.tenantCode())
                .authType(payload.authType())
                .boundAgentId(payload.boundAgentId())
                .boundAgentCode(payload.boundAgentCode())
                .chatKeyId(payload.chatKeyId())
                .skId(payload.skId())
                .build();
        if (UserUtils.AUTH_CHAT_KEY.equals(payload.authType())) {
            if (payload.chatKeyId() == null || payload.boundAgentCode() == null
                    || !payload.boundAgentCode().equals(redisUtils.get(
                    RedisKeyBuilder.globalKey("chatkey:" + payload.chatKeyId())))) {
                throw new BusinessException("游客分享链接已失效");
            }
            Long active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + TableConst.AGENT_CHAT_KEY + " k JOIN " + TableConst.AGENT
                            + " a ON a.agent_code = k.agent_code AND a.tenant_id = k.tenant_id"
                            + " WHERE k.chat_key = ? AND k.tenant_id = ? AND a.id = ?"
                            + " AND a.enabled = 1 AND a.published = 1 AND a.external_share_enabled = 1",
                    Long.class, payload.chatKeyId(), payload.tenantId(), payload.boundAgentId());
            if (active == null || active == 0L) {
                throw new BusinessException("游客分享链接已失效");
            }
        } else if (UserUtils.AUTH_SK.equals(payload.authType())) {
            if (payload.skId() == null || !AuthInterceptor.isSkIdValid(payload.skId())) {
                throw new BusinessException("SK 已失效");
            }
        } else {
            Long active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + TableConst.ACCOUNT + " WHERE id = ?",
                    Long.class, payload.userId());
            if (active == null || active == 0L) {
                throw new BusinessException("账号已失效");
            }
            PermsResolver.AccessContext access = permsResolver.resolveAccess(payload.userId(), payload.tenantId());
            detail.setPerms(access.perms());
            detail.setDeptId(access.deptId());
        }
        request.setAttribute(SysConst.USER_DETAIL, detail);
        sessionAccessGuard.requireRead(sessionId);
    }

    /**
     * 校验 sessionId，防止路径注入
     */
    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException("sessionId 不能为空");
        }
        if (!sessionId.matches("[0-9]+")) {
            throw new BusinessException("sessionId 包含非法字符");
        }
        try {
            Long id = Long.valueOf(sessionId);
            if (id <= 0L || !id.toString().equals(sessionId)) {
                throw new BusinessException("sessionId 不合法");
            }
        } catch (NumberFormatException e) {
            throw new BusinessException("sessionId 不合法");
        }
    }

    /**
     * 取小写扩展名
     */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > -1 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
