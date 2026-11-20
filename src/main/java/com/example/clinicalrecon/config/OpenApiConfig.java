package com.example.clinicalrecon.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("临床试验多源事实对账与质疑数据服务")
                .version("1.0.0")
                .description("""
                        对账链路：受理仓(批次/修订) → 受试者访视关联(候选+置信理由) → 事实协调(确认/冲突/缺失/待判) → 质疑闭环 → 分析快照(质量门槛)。
                        任一统计字段可经 /api/reconciled-facts/{id}/trace 回溯到原始来源批次、修订与协调规则。
                        身份：请求头 X-User / X-Role（演示账号见 README，如 dm1/DM、cra1/CRA）。
                        """));
    }
}
