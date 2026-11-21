package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.service.ReadingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 随访阅片：保存每次测量、病灶对应候选、新病灶与不可测原因。
 * 同一检查的更正生成新阅片版本（旧版 SUPERSEDED），只重算该受试者指标。
 */
@RestController
@RequestMapping("/api/readings")
@Tag(name = "靶病灶-随访阅片", description = "阅片版本与逐病灶测量；缺值不记零；新病灶/不可测分别记录")
public class ReadingController {
    private final ReadingService readingService;

    public ReadingController(ReadingService readingService) {
        this.readingService = readingService;
    }

    @PostMapping
    @Operation(summary = "提交阅片（初阅）", description = "保存每次测量；可测须给正直径，不可测须给原因且直径留空；"
            + "提交后自动生成受约束映射候选并重算该受试者指标")
    public LesionDtos.ReadingOut submit(@Valid @RequestBody LesionDtos.ReadingIn in) {
        return readingService.submit(in);
    }

    @PostMapping("/correct")
    @Operation(summary = "阅片更正（新版本）", description = "旧版本置 SUPERSEDED，按新测量生成新版本；"
            + "只重算该受试者指标版本，旧终点可经映射序号回放")
    public LesionDtos.ReadingOut correct(@RequestParam String subjectKey, @RequestParam String scanKey,
                                         @Valid @RequestBody LesionDtos.ReadingCorrectionIn in) {
        return readingService.correct(subjectKey, scanKey, in);
    }

    @GetMapping
    @Operation(summary = "阅片列表", description = "可按 subjectKey 过滤；含每版测量与版本状态")
    public List<LesionDtos.ReadingOut> list(@RequestParam(required = false) String subjectKey) {
        return readingService.listReadings(subjectKey);
    }
}
