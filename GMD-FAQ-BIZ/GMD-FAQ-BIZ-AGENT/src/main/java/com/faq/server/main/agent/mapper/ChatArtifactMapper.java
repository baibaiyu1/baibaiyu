package com.gmd.faq.server.main.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.entity.ChatArtifact;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：对话产物 Mapper
 *
 * <p>{@code IgnoreTenant} 后缀的方法供**智能体执行线程**调用：
 * 该线程上没有请求级租户上下文（与 {@link com.gmd.faq.server.main.engine.log.ConcurrentLogConsumer}
 * 用 JdbcTemplate 绕过拦截器同因），因此忽略拦截器注入、由调用方显式带上 tenant_id。
 * 读路径（控制台请求）仍走默认方法，由拦截器自动隔离租户。
 *
 **/
@Mapper
public interface ChatArtifactMapper extends BaseMapper<ChatArtifact> {

    /**
     * 写入或更新产物（同一会话内按 file_path 去重）
     *
     * <p>文件被重新生成时更新已有行而非插入新行，否则对话里会出现多张指向同一文件的卡片。
     * 同时刷新 create_time，让卡片移动到最近一次产出的位置。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("insert into " + TableConst.CHAT_ARTIFACT
            + " (tenant_id, session_id, file_path, file_name, extension, size_bytes, source_tool, create_time)"
            + " values (#{tenantId}, #{sessionId}, #{filePath}, #{fileName}, #{extension}, #{sizeBytes}, #{sourceTool}, #{createTime})"
            + " on duplicate key update"
            + " file_name = values(file_name),"
            + " extension = values(extension),"
            + " size_bytes = values(size_bytes),"
            + " source_tool = values(source_tool),"
            + " create_time = values(create_time)")
    int upsertIgnoreTenant(ChatArtifact artifact);
}
