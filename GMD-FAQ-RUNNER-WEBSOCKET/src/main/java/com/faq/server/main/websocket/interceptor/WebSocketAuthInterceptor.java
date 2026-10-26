package com.gmd.faq.server.main.websocket.interceptor;

import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.common.util.RedisKeyBuilder;
import com.gmd.faq.server.main.common.util.RedisUtils;
import com.gmd.faq.server.main.common.util.TokenUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 描述：WebSocket 握手拦截器 - 实现连接认证
 *
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {
    private final RedisUtils redisUtils;
    /**
     * 握手前执行
     *
     * @param request    HTTP 请求
     * @param response   HTTP 响应
     * @param wsHandler  WebSocket 处理器
     * @param attributes 属性集合（可用于传递到 WebSocketSession）
     * @return true 允许握手，false 拒绝握手
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        if (request instanceof ServletServerHttpRequest) {

            // 验证 token
            String token = TokenUtils.getToken();
            Claims claims = TokenUtils.parseAndValidateToken(token);
            if (redisUtils.get(RedisKeyBuilder.globalKey("login:" + token)) == null) {
                throw new RuntimeException("用户未登录");
            }

            UserDetail userDetail = JsonUtils.parse(claims.getSubject(), UserDetail.class);
            if (userDetail == null) {
                throw new RuntimeException("Token身份无效");
            }
            if (UserUtils.AUTH_CHAT_KEY.equals(userDetail.getAuthType())) {
                String agentCode = redisUtils.get(RedisKeyBuilder.globalKey("chatkey:" + claims.getId()));
                if (userDetail.getBoundAgentId() == null || userDetail.getBoundAgentCode() == null
                        || !userDetail.getBoundAgentCode().equals(agentCode)) {
                    throw new RuntimeException("分享链接已失效");
                }
                userDetail.setChatKeyId(claims.getId());
            } else if (claims.getId() == null || !claims.getId().matches("[0-9]+")) {
                throw new RuntimeException("Token身份无效");
            }
            // 将用户信息存入 attributes，会在 WebSocketSession 建立后传递
            attributes.put(SysConst.USER_DETAIL, userDetail);
            return true;
        }

        return false;
    }

    /**
     * 握手后执行
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后可以做一些日志记录或其他操作
        if (exception != null) {
            log.error("WebSocket 握手异常：{}", exception.getMessage(), exception);
        }
    }
}
