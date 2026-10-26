package com.gmd.faq.server.main.websocket.context;

import com.gmd.faq.server.main.common.UserDetail;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述：自定义类，包装WebSocketSession
 *
 **/
@Getter
@Setter
public class GmdWebSocketSession {
    private static final Map<String, GmdWebSocketSession> CACHED = new ConcurrentHashMap<>();
    private WebSocketSession webSocketSession;
    private String clientId;
    private UserDetail user;

    private long activateTime = System.currentTimeMillis();
    private long timeout;

    public GmdWebSocketSession(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    public static Map<String, GmdWebSocketSession> getSessionCache() {
        return CACHED;
    }

    /**
     * 判断是否可写
     */
    public boolean writeable() {
        return webSocketSession != null && webSocketSession.isOpen();
    }

    /**
     * 根据WebSocketSession获取GmdWebSocketSession
     * @param session WebSocketSession
     */
    public static GmdWebSocketSession from(WebSocketSession session) {
        GmdWebSocketSession GmdWebSocketSession = CACHED.get(session.getId());
        if (GmdWebSocketSession == null) {
            GmdWebSocketSession = new GmdWebSocketSession(session);
            CACHED.put(session.getId(), GmdWebSocketSession);
        }
        return GmdWebSocketSession;
    }

    /**
     * 根据用户ID获取WebSocketSession集合
     * @param clientId clientId
     */
    public static GmdWebSocketSession getSessionByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }

        for (Map.Entry<String, GmdWebSocketSession> entry : CACHED.entrySet()) {
            GmdWebSocketSession value = entry.getValue();
            if (clientId.equals(value.getClientId())) {
                return value;
            }
        }

        return null;
    }

    /**
     * 根据账号 ID 获取 WebSocketSession 集合
     * @param accountId 账号 ID
     */
    public static List<GmdWebSocketSession> getSessionByAccountId(String accountId) {
        ArrayList<GmdWebSocketSession> targetSessions = new ArrayList<>();
        if (accountId == null) {
            return targetSessions;
        }

        for (Map.Entry<String, GmdWebSocketSession> entry : CACHED.entrySet()) {
            UserDetail user = entry.getValue().getUser();
            if (user != null && accountId.equals(String.valueOf(user.getId()))) {
                targetSessions.add(entry.getValue());
            }
        }

        return targetSessions;
    }

    /**
     * 关闭
     */
    public void close(){
        if(this.webSocketSession != null) {
            remove(this.webSocketSession);
            try {
                if (webSocketSession.isOpen()) {
                    this.webSocketSession.close(CloseStatus.SESSION_NOT_RELIABLE);
                }
            } catch (Exception ignored) {

            }
            this.webSocketSession = null;
        }
    }

    /**
     * 移除 WebSocketSession
     * @param session WebSocketSession
     */
    private static void remove(WebSocketSession session) {
        CACHED.remove(session.getId());
    }
}
