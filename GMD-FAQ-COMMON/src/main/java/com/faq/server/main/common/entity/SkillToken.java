package com.gmd.faq.server.main.common.entity;

import com.gmd.faq.server.main.common.consts.TableConst;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 内部服务鉴权 token（console 与 runner-file 共享）
 * 单表单字段，由 console 启动时生成
 *
 **/
@Getter
@Setter
@TableName(TableConst.SKILL_TOKEN)
public class SkillToken {

    /**
     * 技能同步鉴权 token
     */
    private String skillToken;
}
