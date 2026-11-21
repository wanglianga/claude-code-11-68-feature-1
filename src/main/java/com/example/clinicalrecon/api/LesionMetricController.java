package com.example.clinicalrecon.api;

import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.LesionLinkRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.service.LesionMetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 靶病灶纵向指标：直径总和、相对基线/最低值变化、可判定状态与病灶时间线。
 * 指标按受试者版本化，证据链可回溯到扫描、阅片版本、确认映射与逐次测量。
 */
@RestController
@RequestMapping("/api/lesion-metrics")
@Tag(name = "靶病灶-纵向指标", description = "SLD 总和、基线/最低值变化、可判定状态、时间线与证据链")
public class LesionMetricController {
    private final LesionMetricService metricService;
    private final LesionLinkRepo linkRepo;
    private final MasterDataRepo masterRepo;

    public LesionMetricController(LesionMetricService metricService, LesionLinkRepo linkRepo,
                                  MasterDataRepo masterRepo) {
        this.metricService = metricService;
        this.linkRepo = linkRepo;
        this.masterRepo = masterRepo;
    }

    @GetMapping
    @Operation(summary = "受试者纵向指标", description = "按扫描日期排列；含正式总和、基线/最低值变化百分比、可判定状态与标记")
    public List<LesionDtos.MetricOut> metrics(@RequestParam String subjectKey) {
        long subjectId = masterRepo.findSubjectByKey(subjectKey)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + subjectKey)).id();
        return metricService.metricsOfSubject(subjectId);
    }

    @GetMapping("/timeline")
    @Operation(summary = "病灶时间线", description = "逐测量节点及确认映射的父身份/关系；未确认测量以 UNMAPPED 单列，不进入总和")
    public LesionDtos.TimelineOut timeline(@RequestParam String subjectKey) {
        long subjectId = masterRepo.findSubjectByKey(subjectKey)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + subjectKey)).id();
        return metricService.timeline(subjectId);
    }

    @GetMapping("/{id}/trace")
    @Operation(summary = "指标证据链", description = "回溯扫描协议、阅片版本、确认映射依据与逐次测量；含全部指标版本历史")
    public Map<String, Object> trace(@PathVariable long id) {
        return metricService.traceMetric(id);
    }

    @GetMapping("/runs")
    @Operation(summary = "映射/重算运行序号", description = "按受试者记录每次重算触发原因与变更数；指标版本与疗效数据集据此回放")
    public List<Map<String, Object>> runs() {
        return JsonMaps.camel(linkRepo.listRuns());
    }
}
