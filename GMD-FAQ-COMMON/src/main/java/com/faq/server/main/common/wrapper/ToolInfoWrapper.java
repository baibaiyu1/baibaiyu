package com.gmd.faq.server.main.common.wrapper;

import com.gmd.faq.server.main.common.enums.ScopeType;
import lombok.*;

import java.util.List;

/**
 * 描述：工具信息包装类
 *
 **/
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfoWrapper {
    private String name;
    private String description;
    private List<ParamInfo> params;
    private String classPath;

    /**
     * 作用域类型（null 视为 GLOBAL）
     */
    private ScopeType scopeType;

    /**
     * 目标租户编码列表（仅在 scopeType = TENANT 时生效）
     */
    private List<String> tenantCodes;

    public static class ParamInfo {
        @Getter
        @Setter
        private String name;
        @Getter
        @Setter
        private String description;
        @Getter
        @Setter
        private String type;
        @Getter
        @Setter
        private String defaultValue;
        @Getter
        @Setter
        private Boolean required;
    }
}
