package com.gmd.faq.server.main.common.router;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 聊天消息表路由组件：根据 session.messageTable 动态路由到主表或归档表。
 * 使用 JdbcTemplate 绕过 MyBatis-Plus 租户拦截器，与 ConcurrentLogConsumer 模式一致。
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageTableRouter {

    private static final String MAIN_TABLE = TableConst.CHAT_MESSAGE;

    /** 归档表名后缀分隔符 */
    private static final String ARCHIVE_TABLE_PREFIX = TableConst.CHAT_MESSAGE + "_";

    /** 归档表名校验正则：gmd_faq_chat_message_yyyyMM */
    private static final String TABLE_NAME_PATTERN = "^" + TableConst.CHAT_MESSAGE + "_\\d{6}$";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 解析目标表名：空或 null 返回主表，否则返回 messageTable 本身
     */
    public String resolveTable(String messageTable) {
        if (messageTable == null || messageTable.isBlank()) {
            return MAIN_TABLE;
        }
        validateTableName(messageTable);
        return messageTable;
    }

    /**
     * 从指定表按 ID 查询单条消息
     */
    public ChatMessage getById(Integer id, String messageTable) {
        String table = resolveTable(messageTable);
        if (!tableExists(table)) {
            log.warn("归档表 {} 不存在，无法查询消息 id={}", table, id);
            return null;
        }
        String sql = "SELECT id, tenant_id, session_id, role, content, parent_id, path, depth, created_at FROM "
                + table + " WHERE id = ?";
        List<ChatMessage> result = jdbcTemplate.query(sql, this::mapRow, id);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * 从指定表按 ID 列表查询，按 depth 升序
     */
    public List<ChatMessage> listByIdsOrderByDepth(List<Integer> ids, String messageTable) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String table = resolveTable(messageTable);
        if (!tableExists(table)) {
            log.warn("归档表 {} 不存在，无法批量查询消息", table);
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, tenant_id, session_id, role, content, parent_id, path, depth, created_at FROM "
                + table + " WHERE id IN (" + placeholders + ") ORDER BY depth ASC";
        return jdbcTemplate.query(sql, this::mapRow, ids.toArray());
    }

    /**
     * 从指定表按 sessionId 删除所有消息
     */
    public int deleteBySessionId(Long sessionId, String messageTable) {
        String table = resolveTable(messageTable);
        if (!tableExists(table)) {
            log.warn("归档表 {} 不存在，跳过删除 sessionId={} 的消息", table, sessionId);
            return 0;
        }
        String sql = "DELETE FROM " + table + " WHERE session_id = ?";
        return jdbcTemplate.update(sql, sessionId);
    }

    /**
     * 创建归档表（与 chat_message 同结构）
     */
    public void createArchiveTableIfNotExists(String tableName) {
        validateTableName(tableName);
        if (tableExists(tableName)) {
            log.info("归档表 {} 已存在，跳过创建", tableName);
            return;
        }
        jdbcTemplate.execute("CREATE TABLE " + tableName + " LIKE gmd_faq_chat_message");
        log.info("归档表 {} 创建成功", tableName);
    }

    /**
     * 检查表是否存在（主表默认返回 true，归档表查 information_schema）
     */
    public boolean tableExists(String tableName) {
        if (MAIN_TABLE.equals(tableName)) {
            return true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    /**
     * 校验表名合法性，防止 SQL 注入
     */
    private void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches(TABLE_NAME_PATTERN)) {
            throw new IllegalArgumentException("非法的归档表名: " + tableName + "，表名格式须为 " + ARCHIVE_TABLE_PREFIX + "yyyyMM");
        }
    }

    /**
     * 将 ResultSet 映射为 ChatMessage 实体
     */
    private ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        ChatMessage msg = new ChatMessage();
        msg.setId(rs.getInt("id"));
        msg.setTenantId(rs.getLong("tenant_id"));
        msg.setSessionId(rs.getLong("session_id"));
        msg.setRole(rs.getString("role"));
        msg.setContent(rs.getString("content"));
        msg.setParentId((Integer) rs.getObject("parent_id"));
        msg.setPath(rs.getString("path"));
        msg.setDepth((Integer) rs.getObject("depth"));
        var ts = rs.getTimestamp("created_at");
        if (ts != null) {
            msg.setCreatedAt(ts.toLocalDateTime());
        }
        return msg;
    }
}
