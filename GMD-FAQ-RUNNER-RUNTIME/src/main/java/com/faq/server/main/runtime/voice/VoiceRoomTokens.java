package com.gmd.faq.server.main.runtime.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 每次连接创建独立房间，浏览器只拿到该房间的麦克风权限。 */
@Component
@RequiredArgsConstructor
public class VoiceRoomTokens {
    private final VoiceConfiguration config;
    private final ObjectMapper json;

    public record Connection(String serverUrl, String participantToken, String roomName,
                             String participantIdentity, long expiresAt) {}

    public Connection issue() {
        if (!config.available()) throw new IllegalStateException("语音服务尚未配置，请先启动本地语音服务");
        String room = "gmd-voice-" + UUID.randomUUID();
        String identity = "user-" + UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(600);
        String metadata;
        try { metadata = json.writeValueAsString(Map.of("voiceParticipant", identity)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("语音房间配置失败"); }
        String token = Jwts.builder().issuer(config.getApiKey()).subject(identity)
                .issuedAt(Date.from(now)).notBefore(Date.from(now.minusSeconds(5)))
                .expiration(Date.from(expires))
                .claim("video", Map.of("roomJoin", true, "room", room,
                        "canPublish", true, "canPublishSources", List.of("microphone"),
                        "canSubscribe", true, "canPublishData", true, "canUpdateOwnMetadata", false))
                .claim("roomConfig", Map.of("emptyTimeout", 60, "maxParticipants", 2,
                        "agents", List.of(Map.of("agentName", config.getAgentName(), "metadata", metadata))))
                .signWith(Keys.hmacShaKeyFor(config.getApiSecret().getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        return new Connection(config.getServerUrl(), token, room, identity, expires.toEpochMilli());
    }
}
