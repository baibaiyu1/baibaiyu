package com.gmd.faq.server.main.console.workflow;

import com.gmd.faq.server.main.cache.service.CacheService;
import com.gmd.faq.server.main.channel.entity.Channel;
import com.gmd.faq.server.main.channel.service.ChannelService;
import com.gmd.faq.server.main.common.entity.Cache;
import com.gmd.faq.server.main.common.entity.Datasource;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.datasource.service.DatasourceService;
import com.gmd.faq.server.main.mq.entity.Mq;
import com.gmd.faq.server.main.mq.service.MqService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/workflow/resources")
@RequiredArgsConstructor
public class WorkflowResourceController {
    private final DatasourceService datasourceService;
    private final CacheService cacheService;
    private final MqService mqService;
    private final ChannelService channelService;

    @GetMapping("/summary")
    public R<Map<String, Long>> summary() {
        long datasourceTotal = datasourceService.count();
        long cacheTotal = cacheService.count();
        long mqTotal = mqService.count();
        long datasourceEnabled = datasourceService.lambdaQuery().eq(Datasource::getEnabled, true).count();
        long cacheEnabled = cacheService.lambdaQuery().eq(Cache::getEnabled, true).count();
        long mqEnabled = mqService.lambdaQuery().eq(Mq::getEnabled, true).count();
        long channelTotal = channelService.count();
        long channelEnabled = channelService.lambdaQuery().eq(Channel::getEnabled, true).count();

        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", datasourceTotal + cacheTotal + mqTotal + channelTotal);
        summary.put("datasourceTotal", datasourceTotal);
        summary.put("cacheTotal", cacheTotal);
        summary.put("mqTotal", mqTotal);
        summary.put("channelTotal", channelTotal);
        summary.put("datasourceEnabled", datasourceEnabled);
        summary.put("cacheEnabled", cacheEnabled);
        summary.put("mqEnabled", mqEnabled);
        summary.put("channelEnabled", channelEnabled);
        return R.data(summary);
    }
}
