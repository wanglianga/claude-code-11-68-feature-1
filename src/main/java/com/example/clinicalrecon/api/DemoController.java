package com.example.clinicalrecon.api;

import com.example.clinicalrecon.service.DemoSeedService;
import com.example.clinicalrecon.service.LesionDemoSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@Tag(name = "演示", description = "演示种子与重置")
public class DemoController {
    private final DemoSeedService demoSeedService;
    private final LesionDemoSeedService lesionDemoSeedService;

    public DemoController(DemoSeedService demoSeedService, LesionDemoSeedService lesionDemoSeedService) {
        this.demoSeedService = demoSeedService;
        this.lesionDemoSeedService = lesionDemoSeedService;
    }

    @PostMapping("/seed")
    @Operation(summary = "重置并装载演示数据", description = "幂等：清空后重建主数据并受理 5 个样例批次"
            + "（CRF×2/检验/影像/监查）；lab-batch-2 检验更正修订留给验收流程手工上送")
    public Map<String, Object> seed() {
        return demoSeedService.seed();
    }

    @PostMapping("/lesion-seed")
    @Operation(summary = "装载靶病灶纵向演示数据", description = "幂等：清空靶病灶域后构建六类场景"
            + "（持续/新病灶、分裂、融合、不可测、协议越界、未确认门控），经正式服务链路生成候选与指标")
    public Map<String, Object> lesionSeed() {
        return lesionDemoSeedService.seedAll();
    }

    @PostMapping("/reset")
    @Operation(summary = "清空全部数据")
    public Map<String, Object> reset() {
        demoSeedService.reset();
        return Map.of("message", "已清空");
    }
}
