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
                .title("临床试验多源事实对账与靶病灶纵向数据服务")
                .version("1.0.0")
                .description("""
                        对账链路：受理仓(批次/修订) → 受试者访视关联(候选+置信理由) → 事实协调(确认/冲突/缺失/待判) → 质疑闭环 → 分析快照(质量门槛)。
                        任一统计字段可经 /api/reconciled-facts/{id}/trace 回溯到原始来源批次、修订与协调规则。
                        靶病灶纵向链路：影像检查/序列与基线病灶登记 → 随访阅片(版本化测量/新病灶/不可测原因)
                          → 受约束映射候选与人工确认(分裂/融合父子关系，未确认不入正式总和)
                          → 指标版本(SLD 总和、相对基线/最低值变化、可判定/不可比较) → 疗效数据集(扫描/阅片版本/映射依据，可回放复算)。
                        身份：请求头 X-User / X-Role（演示账号见 README，如 dm1/DM、cra1/CRA、rad1/RAD）。
                        """));
    }
}
