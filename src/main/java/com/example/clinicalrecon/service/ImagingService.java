package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.ConflictException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.ImagingRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 影像主数据服务：检查/序列登记（携带设备、层厚等协议元数据）与靶病灶登记。
 * 协议越界（设备或层厚超出方案允许范围）在扫描层面标记 comparableToBaseline=false，
 * 下游计算器据此把变化标为不可比较，而不是进展。
 */
@Service
public class ImagingService {
    private final ImagingRepo imagingRepo;
    private final MasterDataRepo masterRepo;
    private final BigDecimal maxSliceThickness;

    public ImagingService(ImagingRepo imagingRepo, MasterDataRepo masterRepo,
                          @Value("${app.lesion-protocol.max-slice-thickness-mm:3.0}") BigDecimal maxSliceThickness) {
        this.imagingRepo = imagingRepo;
        this.masterRepo = masterRepo;
        this.maxSliceThickness = maxSliceThickness;
    }

    @Transactional
    public LesionDtos.ScanOut createScan(LesionDtos.ScanIn in) {
        var subject = masterRepo.findSubjectByKey(in.subjectKey())
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + in.subjectKey()));
        if (imagingRepo.findScan(subject.id(), in.scanKey()).isPresent()) {
            throw new ConflictException("检查已登记: " + in.subjectKey() + "/" + in.scanKey() + "（幂等键冲突）");
        }
        String protocolStatus = in.protocolStatus() == null ? Enums.Protocol.OK : in.protocolStatus();
        String protocolReason = in.protocolReason();
        boolean comparable = in.comparableToBaseline() == null || in.comparableToBaseline();
        // 层厚超出方案允许范围 → 自动判协议越界（相似地，显式 PROTOCOL_DEVIATION 也强制不可比较）
        if (in.sliceThickness() != null && in.sliceThickness().compareTo(maxSliceThickness) > 0
                && Enums.Protocol.OK.equals(protocolStatus)) {
            protocolStatus = Enums.Protocol.DEVIATION;
            protocolReason = "层厚 " + in.sliceThickness() + "mm 超出方案允许最大层厚 "
                    + maxSliceThickness + "mm";
        }
        if (Enums.Protocol.DEVIATION.equals(protocolStatus)) {
            if (protocolReason == null || protocolReason.isBlank()) {
                throw new BadRequestException("协议越界(PROTOCOL_DEVIATION)必须填写 protocolReason");
            }
            comparable = false;
        }
        long scanId = imagingRepo.insertScan(subject.id(), in.centerId(), in.scanKey(), in.visitCode(),
                in.scanDate(), in.modality() == null ? "CT" : in.modality(), in.scannerModel(),
                in.sliceThickness(), in.contrastSeries(), protocolStatus, protocolReason,
                comparable, UserContext.user());
        var seriesOut = new ArrayList<Map<String, Object>>();
        if (in.series() != null) {
            for (var s : in.series()) {
                long sid = imagingRepo.insertSeries(scanId, s.seriesKey(), s.modality(),
                        s.sequenceLabel(), s.sliceThickness());
                seriesOut.add(Map.of("id", sid, "seriesKey", s.seriesKey(), "sequenceLabel", s.sequenceLabel(),
                        "sliceThickness", s.sliceThickness() == null ? "" : s.sliceThickness()));
            }
        }
        return toScanOut(imagingRepo.findScanById(scanId).orElseThrow(), seriesOut);
    }

    @Transactional
    public LesionDtos.LesionOut registerLesion(LesionDtos.LesionIn in) {
        var subject = masterRepo.findSubjectByKey(in.subjectKey())
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + in.subjectKey()));
        if (imagingRepo.findLesionByKey(in.lesionKey()).isPresent()) {
            throw new ConflictException("病灶编号已登记: " + in.lesionKey());
        }
        if (!Enums.Axis.ALL.contains(in.measurementAxis())) {
            throw new BadRequestException("measurementAxis 须为 LONG_AXIS/SHORT_AXIS: " + in.measurementAxis());
        }
        var scan = imagingRepo.findScan(subject.id(), in.scanKey())
                .orElseThrow(() -> new NotFoundException("检查不存在: " + in.scanKey()));
        Long seriesId = imagingRepo.findSeriesId(scan.id(), in.seriesKey()).orElse(null);
        if (in.seriesKey() != null && seriesId == null) {
            throw new BadRequestException("影像序列不在该检查中: " + in.seriesKey());
        }
        String origin = in.origin() == null ? Enums.LesionOrigin.BASELINE : in.origin();
        if (!Enums.LesionOrigin.ALL.contains(origin)) {
            throw new BadRequestException("origin 须为 BASELINE/NEW/DERIVED: " + origin);
        }
        long id = imagingRepo.insertLesion(subject.id(), in.lesionKey(), in.bodySite(), in.measurementAxis(),
                scan.id(), seriesId, origin, in.note(), UserContext.user());
        return toLesionOut(imagingRepo.findLesionById(id).orElseThrow());
    }

    public List<LesionDtos.ScanOut> listScans(String subjectKey) {
        var rows = subjectKey == null ? imagingRepo.allScans()
                : imagingRepo.scansOfSubject(resolveSubjectId(subjectKey));
        var out = new ArrayList<LesionDtos.ScanOut>();
        for (var r : rows) out.add(toScanOut(r, imagingRepo.seriesOfScan(r.id())));
        return out;
    }

    public List<LesionDtos.LesionOut> listLesions(String subjectKey) {
        if (subjectKey == null) {
            return masterRepo.listSubjects().stream()
                    .flatMap(s -> imagingRepo.lesionsOfSubject(((Number) s.get("id")).longValue()).stream())
                    .map(this::toLesionOut).toList();
        }
        return imagingRepo.lesionsOfSubject(resolveSubjectId(subjectKey)).stream()
                .map(this::toLesionOut).toList();
    }

    private long resolveSubjectId(String subjectKey) {
        return masterRepo.findSubjectByKey(subjectKey)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + subjectKey)).id();
    }

    LesionDtos.ScanOut toScanOut(ImagingRepo.ScanRow r, List<Map<String, Object>> series) {
        String key = masterRepo.findSubjectById(r.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        return new LesionDtos.ScanOut(r.id(), r.subjectId(), key, r.centerId(), r.scanKey(), r.visitCode(),
                r.scanDate(), r.modality(), r.scannerModel(), r.sliceThickness(), r.protocolStatus(),
                r.protocolReason(), r.comparableToBaseline(), series);
    }

    LesionDtos.LesionOut toLesionOut(ImagingRepo.LesionRow r) {
        String subjectKey = masterRepo.findSubjectById(r.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        String scanKey = imagingRepo.findScanById(r.baselineScanId())
                .map(ImagingRepo.ScanRow::scanKey).orElse("?");
        return new LesionDtos.LesionOut(r.id(), r.subjectId(), subjectKey, r.lesionKey(), r.bodySite(),
                r.measurementAxis(), r.baselineScanId(), scanKey, r.baselineSeriesId(), r.origin(),
                r.status(), r.note(), r.registeredBy(),
                r.registeredAt() == null ? null : r.registeredAt().toLocalDate());
    }
}
