package com.gmd.faq.server.main.engine.tool.builtins;

import com.gmd.faq.server.main.common.annotation.Scope;
import com.gmd.faq.server.main.common.enums.ScopeType;
import com.gmd.faq.server.main.engine.tool.IAgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;

/**
 * 描述：获取当前时间
 *
 **/
@Component
@Scope(ScopeType.GLOBAL)
public class GetCurrentTimeTool implements IAgentTool {

    @Tool(name = "get_current_datetime", description = "获取当前的日期时间")
    public Object getCurrentDateTime(
            @ToolParam(
                    name = "format",
                    description = "日期时间格式，默认值 yyyy-MM-dd HH:mm:ss",
                    required = false)
            String format) {

        if (format == null || format.isEmpty()) {
            format = "yyyy-MM-dd HH:mm:ss";
        }

        return new SimpleDateFormat(format).format(System.currentTimeMillis());
    }
}
