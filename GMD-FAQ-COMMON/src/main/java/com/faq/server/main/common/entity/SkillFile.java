package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.enums.SkillFileType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：技能包文件
 *
 **/
@Getter
@Setter
@TableName(value = TableConst.SKILL_FILE)
public class SkillFile extends BaseTenantEntity {

    /**
     * 技能包ID
     */
    private Long skillId;

    /**
     * 文件类型
     */
    private SkillFileType fileType;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 相对路径（相对于技能包根目录），如 "scripts/helper.py"
     */
    private String filePath;

    /**
     * 文件内容
     */
    private String content;

    /**
     * 排序
     */
    private Integer sort;
}
