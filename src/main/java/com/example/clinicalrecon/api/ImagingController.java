package com.example.clinicalrecon.api;

import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.service.ImagingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 影像主数据：检查（含设备/层厚协议元数据）、影像序列与靶病灶登记。
 * 基线病灶登记部位、测量轴、影像序列和研究受试者。
 */
@RestController
@RequestMapping("/api/imaging")
@Tag(name = "靶病灶-影像登记", description = "检查/序列登记与靶病灶基线登记；协议越界扫描标记为不可比较")
public class ImagingController {
    private final ImagingService imagingService;

    public ImagingController(ImagingService imagingService) {
        this.imagingService = imagingService;
    }

    @PostMapping("/scans")
    @Operation(summary = "登记影像检查", description = "携带扫描仪型号、层厚、增强序列等协议元数据；"
            + "protocolStatus=PROTOCOL_DEVIATION 时强制 comparableToBaseline=false，下游变化标不可比较而非进展")
    public LesionDtos.ScanOut createScan(@Valid @RequestBody LesionDtos.ScanIn in) {
        return imagingService.createScan(in);
    }

    @GetMapping("/scans")
    @Operation(summary = "检查列表", description = "可按 subjectKey 过滤；含每次检查的影像序列")
    public List<LesionDtos.ScanOut> scans(@RequestParam(required = false) String subjectKey) {
        return imagingService.listScans(subjectKey);
    }

    @PostMapping("/lesions")
    @Operation(summary = "登记靶病灶", description = "记录部位、测量轴、影像序列与研究受试者；"
            + "随访新发现病灶用 origin=NEW 登记，基线病灶用默认 BASELINE")
    public LesionDtos.LesionOut registerLesion(@Valid @RequestBody LesionDtos.LesionIn in) {
        return imagingService.registerLesion(in);
    }

    @GetMapping("/lesions")
    @Operation(summary = "靶病灶列表", description = "可按 subjectKey 过滤")
    public List<LesionDtos.LesionOut> lesions(@RequestParam(required = false) String subjectKey) {
        return imagingService.listLesions(subjectKey);
    }
}
