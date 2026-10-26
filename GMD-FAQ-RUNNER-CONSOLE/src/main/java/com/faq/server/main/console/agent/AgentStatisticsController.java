package com.gmd.faq.server.main.console.agent;

import com.gmd.faq.server.main.agent.service.AgentStatisticsService;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.AgentStatisticsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 描述：智能体统计分析Controller
 *
 **/
@RestController
@RequestMapping("/agent/statistics")
@RequiredArgsConstructor
public class AgentStatisticsController {

    private final AgentStatisticsService agentStatisticsService;

    /**
     * 获取智能体趋势统计数据
     *
     * @param agentId 智能体ID
     * @param days    天数范围（3/7/15/30/90）
     * @return 统计数据
     */
    @GetMapping("/{agentId}/trends")
    public R<AgentStatisticsVO> trends(@PathVariable("agentId") Long agentId,
                                       @RequestParam(value = "days", defaultValue = "7") Integer days) {
        return R.data(agentStatisticsService.getAgentTrends(agentId, days));
    }
}
