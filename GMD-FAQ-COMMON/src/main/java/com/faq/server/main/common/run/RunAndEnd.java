package com.gmd.faq.server.main.common.run;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringBootConfiguration;

/**
 * 项目启动前后的配置
 *
 **/
@SpringBootConfiguration
public class RunAndEnd implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        System.out.println("*****************************************************************************************");
        System.out.println("* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *");
        System.out.println("* * * * * * * * * * * * * * Application Successfully Started * * * * *  * * * * * * * * *");
        System.out.println("* * * * * * * * * * * * Welcome to GMD-FAQ-SERVER-MAIN * * * * * * * * * * * * * * * * *");
        System.out.println("* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *");
        System.out.println("*****************************************************************************************");
    }
}
