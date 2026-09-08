package com.pm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目管理系统后端启动类
 */
@SpringBootApplication
@MapperScan("com.pm.mapper") // 扫描 Mapper 接口
@EnableScheduling // 开启定时任务（需求状态推进：READY→DEV 每小时扫描）
public class PmApplication {

    public static void main(String[] args) {
        SpringApplication.run(PmApplication.class, args);
    }
}
