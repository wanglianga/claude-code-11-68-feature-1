package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.ImagingRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.ReadingRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 随访阅片服务：同一检查的每次阅片都是一个版本，更正产生新版本并把旧版本置 SUPERSEDED。
 * 测量铁律：
 *  - 可测必须给正直径；不可测必须给原因且直径留空——缺值绝不记零；
 *  - 新发现病灶须先以 origin=NEW 登记，再在测量中以 isNew=true 引用；
 *  - 阅片落库后自动生成受约束映射候选，未确认候选不进入正式总和。
 */
@Service
public class ReadingService {
    private final ReadingRepo readingRepo;
    private final ImagingRepo imagingRepo;
    private final MasterDataRepo masterRepo;
    private final LesionLinkingService linkingService;
    private final LesionMetricService metricService;

    public ReadingService(ReadingRepo readingRepo, ImagingRepo imagingRepo, MasterDataRepo masterRepo,
                          LesionLinkingService linkingService, LesionMetricService metricService) {
        this.readingRepo = readingRepo;
        this.imagingRepo = imagingRepo;
        this.masterRepo = masterRepo;
        this.linkingService = linkingService;
        this.metricService = metricService;
    }

    @Transactional
    public LesionDtos.ReadingOut submit(LesionDtos.ReadingIn in) {
        var subject = masterRepo.findSubjectByKey(in.subjectKey())
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + in.subjectKey()));
        var scan = imagingRepo.findScan(subject.id(), in.scanKey())
                .orElseThrow(() -> new NotFoundException("检查不存在: " + in.scanKey()));
        if (readingRepo.latestReadingOfScan(scan.id()).isPresent()) {
            throw new BadRequestException("该检查已存在阅片版本，更正请使用 /api/readings/correct 生成新版本");
        }
        String status = in.status() == null ? Enums.Reading.FINAL : in.status();
        long readingId = readingRepo.insertReading(subject.id(), scan.id(), 1, in.reader(), in.readAt(),
                status, in.triggerNote() == null ? "初阅" : in.triggerNote(), UserContext.user());
        persistMeasurements(readingId, subject.id(), scan.id(), in.measurements());

        var auto = linkingService.generateForReading(readingId);
        if (Enums.Reading.FINAL.equals(status)) {
            metricService.recomputeSubject(subject.id(),
                    "reading:submit " + scan.scanKey() + " v1 by " + UserContext.user());
        }
        return toOut(readingRepo.findReadingById(readingId).orElseThrow(), auto.messages());
    }

    /** 阅片更正：旧版本置 SUPERSEDED，生成新版本，仅重算该受试者指标 */
    @Transactional
    public LesionDtos.ReadingOut correct(String subjectKey, String scanKey, LesionDtos.ReadingCorrectionIn in) {
        var subject = masterRepo.findSubjectByKey(subjectKey)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + subjectKey));
        var scan = imagingRepo.findScan(subject.id(), scanKey)
                .orElseThrow(() -> new NotFoundException("检查不存在: " + scanKey));
        var previous = readingRepo.latestReadingOfScan(scan.id())
                .orElseThrow(() -> new BadRequestException("该检查尚无阅片，无法更正，请先提交初阅"));

        readingRepo.supersedeReadingsOfScan(scan.id());
        int version = readingRepo.nextReadingVersion(scan.id());
        long readingId = readingRepo.insertReading(subject.id(), scan.id(), version, in.reader(), in.readAt(),
                Enums.Reading.FINAL,
                "更正(v" + version + "，替代 v" + previous.readingVersion() + "): "
                        + (in.triggerNote() == null ? "" : in.triggerNote()),
                UserContext.user());
        persistMeasurements(readingId, subject.id(), scan.id(), in.measurements());

        var auto = linkingService.generateForReading(readingId);
        metricService.recomputeSubject(subject.id(),
                "reading:correct " + scan.scanKey() + " v" + version + " by " + UserContext.user());
        return toOut(readingRepo.findReadingById(readingId).orElseThrow(), auto.messages());
    }

    private void persistMeasurements(long readingId, long subjectId, long scanId,
                                     List<LesionDtos.MeasurementIn> measurements) {
        if (measurements == null || measurements.isEmpty()) {
            throw new BadRequestException("阅片至少包含一条病灶测量");
        }
        var seen = new java.util.HashSet<String>();
        for (var m : measurements) {
            var lesion = imagingRepo.findLesionByKey(m.lesionKey())
                    .orElseThrow(() -> new NotFoundException(
                            "病灶未登记: " + m.lesionKey() + "（新发现病灶请先以 origin=NEW 登记）"));
            if (lesion.subjectId() != subjectId) {
                throw new BadRequestException("病灶 " + m.lesionKey() + " 不属于该受试者");
            }
            if (!seen.add(m.lesionKey())) {
                throw new BadRequestException("同一阅片中病灶重复测量: " + m.lesionKey());
            }
            boolean measurable = m.measurable() == null || m.measurable();
            boolean isNew = m.isNew() != null && m.isNew();
            if (isNew && !Enums.LesionOrigin.NEW.equals(lesion.origin())) {
                throw new BadRequestException("isNew=true 的病灶必须以 origin=NEW 登记: " + m.lesionKey());
            }
            if (m.axis() != null && !Enums.Axis.ALL.contains(m.axis())) {
                throw new BadRequestException("axis 须为 LONG_AXIS/SHORT_AXIS: " + m.lesionKey());
            }
            Long seriesId = imagingRepo.findSeriesId(scanId, m.seriesKey()).orElse(null);
            if (m.seriesKey() != null && seriesId == null) {
                throw new BadRequestException("影像序列不在该检查中: " + m.seriesKey());
            }
            if (measurable) {
                if (m.diameter() == null || m.diameter().signum() <= 0) {
                    throw new BadRequestException("可测病灶必须给正直径，缺值不得记零: " + m.lesionKey());
                }
                if (m.nonMeasurableReason() != null) {
                    throw new BadRequestException("可测病灶不应填写不可测原因: " + m.lesionKey());
                }
            } else {
                if (m.diameter() != null) {
                    throw new BadRequestException("不可测病灶直径必须留空（缺值不记零，禁止写 0）: " + m.lesionKey());
                }
                if (m.nonMeasurableReason() == null || m.nonMeasurableReason().isBlank()) {
                    throw new BadRequestException("不可测病灶必须记录 nonMeasurableReason: " + m.lesionKey());
                }
                imagingRepo.updateLesionStatus(lesion.id(), Enums.LesionStatus.NON_MEASURABLE);
            }
            readingRepo.insertMeasurement(new ReadingRepo.MeasurementRow(0, readingId, scanId, subjectId,
                    lesion.id(), isNew, measurable,
                    measurable ? null : m.nonMeasurableReason(),
                    measurable ? m.diameter() : null,
                    m.axis(), seriesId, m.contourRef(), m.evidenceRef(), null));
        }
    }

    public List<LesionDtos.ReadingOut> listReadings(String subjectKey) {
        if (subjectKey == null) {
            return readingRepo.allReadings().stream().map(r -> toOut(r, List.of())).toList();
        }
        long subjectId = masterRepo.findSubjectByKey(subjectKey)
                .orElseThrow(() -> new NotFoundException("受试者不存在: " + subjectKey)).id();
        return readingRepo.readingsOfSubject(subjectId).stream().map(r -> toOut(r, List.of())).toList();
    }

    LesionDtos.ReadingOut toOut(ReadingRepo.ReadingRow r, List<String> messages) {
        String subjectKey = masterRepo.findSubjectById(r.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        String scanKey = imagingRepo.findScanById(r.scanId()).map(ImagingRepo.ScanRow::scanKey).orElse("?");
        var meas = new ArrayList<Map<String, Object>>();
        for (var row : readingRepo.measurementViewOfReading(r.id())) meas.add(row);
        var out = new LesionDtos.ReadingOut(r.id(), r.subjectId(), subjectKey, r.scanId(), scanKey,
                r.readingVersion(), r.reader(), r.readAt(), r.status(), r.triggerNote(), meas);
        if (messages != null && !messages.isEmpty()) {
            // 提示信息仅用于提交响应，列表查询不附带
            return out;
        }
        return out;
    }
}
