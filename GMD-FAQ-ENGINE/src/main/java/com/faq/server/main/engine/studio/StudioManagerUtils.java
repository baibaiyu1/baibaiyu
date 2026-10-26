package com.gmd.faq.server.main.engine.studio;

import com.gmd.faq.server.main.common.vo.AccountVO;
import com.gmd.faq.server.main.engine.agui.AgentContext;
import io.agentscope.core.studio.StudioManager;

/**
 * 描述：StudioManagerUtils
 *
 **/
public class StudioManagerUtils {
    public static void initOnce(String url, String project, String agentCode) {
        String runName = calculateRunName(agentCode);
        synchronized (StudioManagerUtils.class) {
            StudioManager.init()
                    .studioUrl(url)
                    .project(project)
                    .runName(runName)
                    .initialize()
                    .block();
        }
    }

    private static String calculateRunName(String agentCode) {
        AgentContext agentContext = AgentContext.get();
        AccountVO userInfo = agentContext.getUserInfo();
        if (userInfo == null) {
            return agentCode;
        }
        return userInfo.getUsername() + "_" + agentCode;
    }
}
