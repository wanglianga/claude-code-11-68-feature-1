package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.ReadingRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 靶病灶纵向演示数据：覆盖六类形态/质量场景，全部经正式服务链路落库（候选生成、指标重算真实执行）。
 *  - SUBJ-001 持续 + 随访新病灶（新病灶不并入 SLD）
 *  - SUBJ-002 分裂（父子关系保留，父基线直径不重复比较，自动高置信确认）
 *  - SUBJ-003 融合（多父归一，自动确认一条 + 人工补确认第二条）
 *  - SUBJ-004 不可测 + 扫描协议越界（不可比较而非进展）+ 未确认候选门控
 */
@Service
public class LesionDemoSeedService {
    private final JdbcTemplate jdbc;
    private final ImagingService imagingService;
    private final ReadingService readingService;
    private final LesionLinkingService linkingService;
    private final ReadingRepo readingRepo;

    public LesionDemoSeedService(JdbcTemplate jdbc, ImagingService imagingService,
                                 ReadingService readingService, LesionLinkingService linkingService,
                                 ReadingRepo readingRepo) {
        this.jdbc = jdbc;
        this.imagingService = imagingService;
        this.readingService = readingService;
        this.linkingService = linkingService;
        this.readingRepo = readingRepo;
    }

    /** 清空靶病灶域全部表（保留 subject 主数据），使种子可重复装载。 */
    @Transactional
    public void resetLesion() {
        jdbc.update("""
                truncate table endpoint_dataset_row, endpoint_dataset,
                    lesion_metric_history, lesion_metric, lesion_mapping_run,
                    lesion_link, lesion_link_candidate, lesion_measurement, reading_session,
                    target_lesion, imaging_series, imaging_scan
                restart identity cascade""");
    }

    @Transactional
    public Map<String, Object> seedAll() {
        resetLesion();
        UserContext.set("rad1", Enums.Role.RAD);
        try {
            // 自包含：确保受试者主数据存在（幂等），不依赖先装载事实对账种子
            jdbc.update("""
                    insert into subject(trial_id, center_id, subject_key, enrolled_date) values
                    ('ONCO-2026-01','C01','SUBJ-001', ?),
                    ('ONCO-2026-01','C01','SUBJ-002', ?),
                    ('ONCO-2026-01','C02','SUBJ-003', ?),
                    ('ONCO-2026-01','C02','SUBJ-004', ?)
                    on conflict (subject_key) do nothing""",
                    java.sql.Date.valueOf(LocalDate.of(2026, 3, 1)),
                    java.sql.Date.valueOf(LocalDate.of(2026, 3, 1)),
                    java.sql.Date.valueOf(LocalDate.of(2026, 3, 1)),
                    java.sql.Date.valueOf(LocalDate.of(2026, 3, 1)));

            seedPersistenceAndNew();
            seedSplit();
            seedFusion();
            seedNonMeasurableAndProtocol();

            var summary = new LinkedHashMap<String, Object>();
            summary.put("SUBJ-001", "持续缩小 + 随访新病灶（NEW 不入 SLD）");
            summary.put("SUBJ-002", "病灶分裂为两个可测部分（自动 SPLIT，父直径不重复比较）");
            summary.put("SUBJ-003", "两个病灶融合（自动+人工确认，FUSION）");
            summary.put("SUBJ-004", "协议越界不可比较 + 不可测原因 + 未确认候选门控");
            return summary;
        } finally {
            UserContext.clear();
        }
    }

    private LesionDtos.SeriesIn series(String key, String label, double thickness) {
        return new LesionDtos.SeriesIn(key, "CT", label, BigDecimal.valueOf(thickness));
    }

    private LesionDtos.MeasurementIn meas(String lesionKey, double diameter, String axis,
                                          String seriesKey, String contour) {
        return new LesionDtos.MeasurementIn(lesionKey, false, true, BigDecimal.valueOf(diameter), axis,
                seriesKey, null, contour, "img-ref-" + lesionKey);
    }

    private void scan(String subject, String center, String scanKey, String visit, LocalDate date,
                      double thickness, String scanner, String protocol, String reason, Boolean comparable) {
        imagingService.createScan(new LesionDtos.ScanIn(subject, center, scanKey, visit, date, "CT", scanner,
                BigDecimal.valueOf(thickness), "增强", protocol, reason, comparable,
                List.of(series(scanKey + "-S1", "胸腹部增强CT轴位", thickness))));
    }

    private void lesion(String subject, String key, String site, String axis, String scanKey,
                        String seriesKey, String origin, String contour) {
        imagingService.registerLesion(new LesionDtos.LesionIn(subject, key, site, axis, scanKey,
                seriesKey, origin, "轮廓=" + contour));
    }

    private void reading(String subject, String scanKey, String reader, LocalDate readAt,
                         List<LesionDtos.MeasurementIn> measurements) {
        readingService.submit(new LesionDtos.ReadingIn(subject, scanKey, reader, readAt,
                Enums.Reading.FINAL, null, measurements));
    }

    // ---------- SUBJ-001：持续 + 新病灶 ----------
    private void seedPersistenceAndNew() {
        String s = "SUBJ-001";
        scan(s, "C01", "S1-BL", "V1", LocalDate.of(2026, 3, 2), 2.0, "SCANNER-A",
                Enums.Protocol.OK, null, null);
        scan(s, "C01", "S1-FU1", "V2", LocalDate.of(2026, 3, 16), 2.0, "SCANNER-A",
                Enums.Protocol.OK, null, null);
        scan(s, "C01", "S1-FU2", "V3", LocalDate.of(2026, 3, 30), 2.0, "SCANNER-A",
                Enums.Protocol.OK, null, null);

        lesion(s, "S1-TL01", "肺右上叶", Enums.Axis.LONG, "S1-BL", "S1-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 10.0, y: 20.0, z: 30.0");
        lesion(s, "S1-TL02", "肺左上叶", Enums.Axis.LONG, "S1-BL", "S1-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 50.0, y: 60.0, z: 70.0");
        reading(s, "S1-BL", "rad1", LocalDate.of(2026, 3, 3), List.of(
                meas("S1-TL01", 30, Enums.Axis.LONG, "S1-BL-S1", "x: 10.0, y: 20.0, z: 30.0"),
                meas("S1-TL02", 20, Enums.Axis.LONG, "S1-BL-S1", "x: 50.0, y: 60.0, z: 70.0")));

        // 随访 1：持续缩小；新发现病灶 S1-TL03 先以 NEW 登记
        lesion(s, "S1-TL03", "肺右下叶", Enums.Axis.LONG, "S1-FU1", "S1-FU1-S1", Enums.LesionOrigin.NEW,
                "x: 80.0, y: 90.0, z: 40.0");
        reading(s, "S1-FU1", "rad1", LocalDate.of(2026, 3, 17), List.of(
                meas("S1-TL01", 27, Enums.Axis.LONG, "S1-FU1-S1", "x: 11.0, y: 21.0, z: 30.0"),
                meas("S1-TL02", 18, Enums.Axis.LONG, "S1-FU1-S1", "x: 51.0, y: 60.0, z: 71.0"),
                new LesionDtos.MeasurementIn("S1-TL03", true, true, BigDecimal.valueOf(12), Enums.Axis.LONG,
                        "S1-FU1-S1", null, "x: 80.0, y: 90.0, z: 40.0", "img-ref-new")));

        // 随访 2：继续缩小
        reading(s, "S1-FU2", "rad1", LocalDate.of(2026, 3, 31), List.of(
                meas("S1-TL01", 24, Enums.Axis.LONG, "S1-FU2-S1", "x: 12.0, y: 22.0, z: 31.0"),
                meas("S1-TL02", 15, Enums.Axis.LONG, "S1-FU2-S1", "x: 52.0, y: 61.0, z: 71.0"),
                new LesionDtos.MeasurementIn("S1-TL03", true, true, BigDecimal.valueOf(10), Enums.Axis.LONG,
                        "S1-FU2-S1", null, "x: 81.0, y: 90.0, z: 40.0", "img-ref-new2")));
    }

    // ---------- SUBJ-002：分裂（自动确认 SPLIT） ----------
    private void seedSplit() {
        String s = "SUBJ-002";
        scan(s, "C01", "S2-BL", "V1", LocalDate.of(2026, 3, 2), 2.0, "SCANNER-A",
                Enums.Protocol.OK, null, null);
        scan(s, "C01", "S2-FU1", "V2", LocalDate.of(2026, 3, 16), 2.0, "SCANNER-A",
                Enums.Protocol.OK, null, null);

        lesion(s, "S2-TL11", "肝右叶", Enums.Axis.LONG, "S2-BL", "S2-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 100.0, y: 100.0, z: 100.0");
        reading(s, "S2-BL", "rad1", LocalDate.of(2026, 3, 3), List.of(
                meas("S2-TL11", 40, Enums.Axis.LONG, "S2-BL-S1", "x: 100.0, y: 100.0, z: 100.0")));

        // 复查分裂为两个可测部分（DERIVED 子代，同部位，轮廓邻近 → 唯一高置信自动 SPLIT）
        lesion(s, "S2-TL12", "肝右叶", Enums.Axis.LONG, "S2-FU1", "S2-FU1-S1", Enums.LesionOrigin.DERIVED,
                "x: 95.0, y: 95.0, z: 100.0");
        lesion(s, "S2-TL13", "肝右叶", Enums.Axis.LONG, "S2-FU1", "S2-FU1-S1", Enums.LesionOrigin.DERIVED,
                "x: 110.0, y: 105.0, z: 100.0");
        reading(s, "S2-FU1", "rad1", LocalDate.of(2026, 3, 17), List.of(
                meas("S2-TL12", 25, Enums.Axis.LONG, "S2-FU1-S1", "x: 95.0, y: 95.0, z: 100.0"),
                meas("S2-TL13", 20, Enums.Axis.LONG, "S2-FU1-S1", "x: 110.0, y: 105.0, z: 100.0")));
    }

    // ---------- SUBJ-003：融合（自动 + 人工） ----------
    private void seedFusion() {
        String s = "SUBJ-003";
        scan(s, "C02", "S3-BL", "V1", LocalDate.of(2026, 3, 2), 2.0, "SCANNER-B",
                Enums.Protocol.OK, null, null);
        scan(s, "C02", "S3-FU1", "V2", LocalDate.of(2026, 3, 16), 2.0, "SCANNER-B",
                Enums.Protocol.OK, null, null);

        lesion(s, "S3-TL21", "纵膈", Enums.Axis.LONG, "S3-BL", "S3-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 200.0, y: 200.0, z: 50.0");
        lesion(s, "S3-TL22", "肺门", Enums.Axis.LONG, "S3-BL", "S3-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 210.0, y: 205.0, z: 50.0");
        reading(s, "S3-BL", "rad2", LocalDate.of(2026, 3, 3), List.of(
                meas("S3-TL21", 25, Enums.Axis.LONG, "S3-BL-S1", "x: 200.0, y: 200.0, z: 50.0"),
                meas("S3-TL22", 15, Enums.Axis.LONG, "S3-BL-S1", "x: 210.0, y: 205.0, z: 50.0")));

        // 两病灶融合为一个可测病灶（DERIVED）。自动只能确认同部位父 S3-TL21；异部位父 S3-TL22 人工补确认
        lesion(s, "S3-TL23", "纵膈", Enums.Axis.LONG, "S3-FU1", "S3-FU1-S1", Enums.LesionOrigin.DERIVED,
                "x: 205.0, y: 202.0, z: 50.0");
        reading(s, "S3-FU1", "rad2", LocalDate.of(2026, 3, 17), List.of(
                meas("S3-TL23", 30, Enums.Axis.LONG, "S3-FU1-S1", "x: 205.0, y: 202.0, z: 50.0")));

        long fuReadingId = readingRepo.readingsOfSubject(jdbc.queryForObject(
                "select id from subject where subject_key='SUBJ-003'", Long.class)).stream()
                .filter(r -> r.scanId() == jdbc.queryForObject(
                        "select id from imaging_scan where scan_key='S3-FU1'", Long.class))
                .findFirst().orElseThrow().id();
        long tl23MeasId = readingRepo.measurementsOfReading(fuReadingId).get(0).id();
        long tl22Id = jdbc.queryForObject("select id from target_lesion where lesion_key='S3-TL22'", Long.class);
        // 人工确认第二条父关系 → 两条边统一标 FUSION
        linkingService.manualLink(new LesionDtos.ManualLinkIn(tl22Id, tl23MeasId,
                Enums.Relation.FUSION, Enums.Confidence.HIGH, "人工复核：两病灶融合为 S3-TL23"));
    }

    // ---------- SUBJ-004：不可测 + 协议越界 + 未确认门控 ----------
    private void seedNonMeasurableAndProtocol() {
        String s = "SUBJ-004";
        scan(s, "C02", "S4-BL", "V1", LocalDate.of(2026, 3, 2), 2.0, "SCANNER-B",
                Enums.Protocol.OK, null, null);
        // 层厚 5.0mm 超方案 3.0mm → 自动判协议越界、不可比较
        scan(s, "C02", "S4-FU1", "V2", LocalDate.of(2026, 3, 16), 5.0, "SCANNER-C",
                Enums.Protocol.OK, null, null);
        scan(s, "C02", "S4-FU2", "V3", LocalDate.of(2026, 3, 30), 2.0, "SCANNER-B",
                Enums.Protocol.OK, null, null);

        lesion(s, "S4-TL31", "肺左下叶", Enums.Axis.LONG, "S4-BL", "S4-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 300.0, y: 300.0, z: 80.0");
        lesion(s, "S4-TL32", "骨(髂骨)", Enums.Axis.LONG, "S4-BL", "S4-BL-S1", Enums.LesionOrigin.BASELINE,
                "x: 10.0, y: 10.0, z: 10.0");
        reading(s, "S4-BL", "rad2", LocalDate.of(2026, 3, 3), List.of(
                meas("S4-TL31", 50, Enums.Axis.LONG, "S4-BL-S1", "x: 300.0, y: 300.0, z: 80.0"),
                meas("S4-TL32", 20, Enums.Axis.LONG, "S4-BL-S1", "x: 10.0, y: 10.0, z: 10.0")));

        // 协议越界复查：即便直径增大也只标不可比较，不判进展；TL32 因伪影不可测（缺值不记零）
        reading(s, "S4-FU1", "rad2", LocalDate.of(2026, 3, 17), List.of(
                meas("S4-TL31", 60, Enums.Axis.LONG, "S4-FU1-S1", "x: 301.0, y: 300.0, z: 80.0"),
                new LesionDtos.MeasurementIn("S4-TL32", false, false, null, null,
                        "S4-FU1-S1", "呼吸运动伪影无法勾画边界", null, "img-ref-nm")));

        // 恢复可比：TL31 高置信自动持续；TL32 改短轴、换序列、轮廓远离 → 仅 LOW 候选，留人工复核（门控）
        reading(s, "S4-FU2", "rad2", LocalDate.of(2026, 3, 31), List.of(
                meas("S4-TL31", 35, Enums.Axis.LONG, "S4-FU2-S1", "x: 302.0, y: 301.0, z: 80.0"),
                new LesionDtos.MeasurementIn("S4-TL32", false, true, BigDecimal.valueOf(18), Enums.Axis.SHORT,
                        "S4-FU2-S1", null, "x: 500.0, y: 500.0, z: 500.0", "img-ref-low")));
    }
}
