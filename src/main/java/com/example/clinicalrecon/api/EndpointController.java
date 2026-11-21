package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.service.EndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 疗效数据集：记录所用扫描、阅片版本与映射依据，供统计团队派生终点，不代替医学裁定。
 * 物化行锚定指标版本；verify 按映射序号从追加历史回放，旧终点可复算、可回放。
 */
@RestController
@RequestMapping("/api/endpoint-datasets")
@Tag(name = "靶病灶-疗效数据集", description = "纵向测量物化与复算；证据含扫描/阅片版本/映射依据")
public class EndpointController {
    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    @Operation(summary = "创建疗效数据集", description = "按门槛（默认仅 EVALUABLE 且可比较）物化受试者×扫描指标；"
            + "每行记录扫描、阅片版本、确认映射与测量依据")
    public LesionDtos.EndpointDatasetOut create(@Valid @RequestBody LesionDtos.EndpointDatasetIn in) {
        return endpointService.create(in);
    }

    @GetMapping
    @Operation(summary = "疗效数据集列表")
    public List<LesionDtos.EndpointDatasetOut> list() {
        return endpointService.listOut();
    }

    @GetMapping("/{id}/rows")
    @Operation(summary = "数据集物化行", description = "含 SLD、基线/最低值变化、可判定状态与完整证据链；物化后不可变")
    public List<LesionDtos.EndpointRowOut> rows(@PathVariable long id) {
        return endpointService.rows(id);
    }

    @GetMapping("/{id}/verify")
    @Operation(summary = "复算验证", description = "以数据集映射序号为界从指标历史重放门槛与物化行比对；"
            + "并给出创建后版本跃迁的受影响受试者，证明旧终点可回放")
    public LesionDtos.EndpointVerifyOut verify(@PathVariable long id) {
        return endpointService.verify(id);
    }
}
