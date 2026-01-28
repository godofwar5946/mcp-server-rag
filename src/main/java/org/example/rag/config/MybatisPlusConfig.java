package org.example.rag.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：扫描 Mapper 接口。
 */
@Configuration
@MapperScan("org.example.rag.mapper")
public class MybatisPlusConfig {
}
