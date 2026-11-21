package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.ImagingRepo;
import com.example.clinicalrecon.repo.LesionLinkRepo;
import com.example.clinicalrecon.repo.LesionMetricRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.ReadingRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 靶病灶指标计算器（映射计算器的度量层）。
 *
 * 正式直径总和（SLD）规则（方案 lesion-recon-v1.0）：
 *  - 仅计入「可测 + 已确认映射 + 具有基线身份（CONTINUATION/SPLIT/FUSION）」的测量；
 *  - 未确认候选的测量一律排除并打 UNCONFIRMED_EXCLUDED，绝不参与正式总和；
 *  - 分裂：子病灶各自计入一次，父病灶基线直径只在基线出现一次，不重复比较；
 *  - 融合：本次测量只计一次；
 *  - 新发现病灶（NEW）不并入靶病灶 SLD，单独打 NEW_LESION 并保留测量，供统计团队派生进展；
 *  - 不可测病灶直径留空（缺值不记零），打 NON_MEASURABLE；
 *  - 协议越界扫描：保留原始测量和，但相对基线变化置空、标 NOT_EVALUABLE/不可比较，而不是判进展。
 *
 * 版本化：更正阅片或映射后只重算该受试者的全部扫描指标（最低值会向后传播），
 * 内容变化的指标行版本跃迁并写追加历史，旧终点据历史序号回放。
 */
@Service
public class LesionMetricService {
    private final LesionMetricRepo metricRepo;
    private final LesionLinkRepo linkRepo;
    private final ImagingRepo imagingRepo;
    private final ReadingRepo readingRepo;
    private final MasterDataRepo masterRepo;
    private final ObjectMapper om = new ObjectMapper();

    public LesionMetricService(LesionMetricRepo metricRepo, LesionLinkRepo linkRepo,
                               ImagingRepo imagingRepo, ReadingRepo readingRepo, MasterDataRepo masterRepo) {
        this.metricRepo = metricRepo;
        this.linkRepo = linkRepo;
        this.imagingRepo = imagingRepo;
        this.readingRepo = readingRepo;
        this.masterRepo = masterRepo;
    }

    private record Computed(long scanId, long readingId, String visitCode, java.time.LocalDate scanDate,
                            BigDecimal sum, BigDecimal baselineSum, BigDecimal nadir,
                            BigDecimal pctBaseline, BigDecimal pctNadir,
                            String status, boolean comparable, List<String> flags,
                            List<Long> contributing, Map<String, Object> basis, String sig) {}

    @Transactional
    public int recomputeSubject(long subjectId, String note) {
        long seq = linkRepo.nextRun(subjectId, note);
        var scans = imagingRepo.scansOfSubject(subjectId);
        if (scans.isEmpty()) {
            linkRepo.finishRun(seq, 0);
            return 0;
        }
        var baselineIds = imagingRepo.baselineLesions(subjectId).stream()
                .map(ImagingRepo.LesionRow::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        long baselineScanId = scans.get(0).id();
        BigDecimal baselineSum = null;
        BigDecimal nadir = null;
        int changed = 0;

        for (var scan : scans) {
            var readingOpt = readingRepo.latestReadingOfScan(scan.id())
                    .filter(r -> Enums.Reading.FINAL.equals(r.status()));
            if (readingOpt.isEmpty()) continue;
            var reading = readingOpt.get();
            boolean isBaseline = scan.id() == baselineScanId;
            var measurements = readingRepo.currentMeasurementsOfScan(subjectId, scan.id());
            var links = linkRepo.linksToMeasurements(measurements.stream()
                    .map(ReadingRepo.MeasurementRow::id).toList());
            Map<Long, LesionLinkRepo.LinkRow> linkByMeas = new LinkedHashMap<>();
            links.forEach(l -> linkByMeas.put(l.toMeasurementId(), l));

            var flags = new LinkedHashSet<String>();
            var contributing = new ArrayList<Long>();
            var formal = new ArrayList<ReadingRepo.MeasurementRow>();
            var newOnes = new ArrayList<ReadingRepo.MeasurementRow>();
            var nonMeasurable = new ArrayList<ReadingRepo.MeasurementRow>();
            var unconfirmed = new ArrayList<ReadingRepo.MeasurementRow>();
            var relations = new LinkedHashSet<String>();

            for (var m : measurements) {
                var link = linkByMeas.get(m.id());
                if (m.isNew() || (link != null && Enums.Relation.NEW.equals(link.relationType()))) {
                    // 新发现：不并入靶病灶 SLD，单独记录，供统计派生进展（缺值不记零，更不计零）
                    newOnes.add(m);
                    flags.add(Enums.LesionFlag.NEW_LESION);
                    continue;
                }
                if (!m.measurable()) {
                    // 不可测：直径留空（缺值不记零），打标并排除
                    nonMeasurable.add(m);
                    flags.add(Enums.LesionFlag.NON_MEASURABLE);
                    continue;
                }
                if (isBaseline) {
                    // 基线测量即靶病灶身份来源，本身构成基线总和（无需映射边）
                    formal.add(m);
                    contributing.add(m.lesionId());
                    continue;
                }
                if (link == null) {
                    // 随访可测但无确认映射：未确认候选不得参与正式总和
                    unconfirmed.add(m);
                    flags.add(Enums.LesionFlag.UNCONFIRMED_EXCLUDED);
                    continue;
                }
                relations.add(link.relationType());
                formal.add(m);
                contributing.add(m.lesionId());
            }
            if (relations.contains(Enums.Relation.SPLIT)) flags.add(Enums.LesionFlag.SPLIT);
            if (relations.contains(Enums.Relation.FUSION)) flags.add(Enums.LesionFlag.FUSION);

            BigDecimal sum = formal.isEmpty() ? null
                    : formal.stream().map(ReadingRepo.MeasurementRow::diameter)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean protocolDeviation = !scan.comparableToBaseline();
            if (protocolDeviation) flags.add(Enums.LesionFlag.PROTOCOL_DEVIATION);

            // 身份完整性：每个基线靶病灶在本次都要有确认归属（确认边的父身份，或明确不可测）
            var accountedParents = links.stream().map(LesionLinkRepo.LinkRow::fromLesionId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            nonMeasurable.forEach(m -> accountedParents.add(m.lesionId()));
            boolean identityComplete = baselineIds.stream().allMatch(accountedParents::contains)
                    && unconfirmed.isEmpty();

            boolean comparable = !protocolDeviation;
            boolean evaluable;
            BigDecimal pctBaseline = null, pctNadir = null;

            if (isBaseline) {
                baselineSum = sum;
                nadir = sum;
                evaluable = sum != null && comparable;
                if (evaluable) {
                    pctBaseline = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
                    pctNadir = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
                }
            } else {
                evaluable = comparable && identityComplete && nonMeasurable.isEmpty()
                        && sum != null && baselineSum != null;
                if (comparable) {
                    // 协议越界时变化置空，绝不把不可比较当作进展；最低值也不更新
                    pctBaseline = pct(sum, baselineSum);
                    if (nadir != null) pctNadir = pct(sum, nadir);
                    if (sum != null) nadir = (nadir == null) ? sum : nadir.min(sum);
                }
            }

            String sig = sigOf(links, measurements, reading.readingVersion(), scan.protocolStatus(),
                    scan.comparableToBaseline());
            var basis = buildBasis(seq, reading, links, formal, newOnes, nonMeasurable, unconfirmed,
                    scan, isBaseline, sig);

            var computed = new Computed(scan.id(), reading.id(), scan.visitCode(), scan.scanDate(),
                    sum, baselineSum, nadir, pctBaseline, pctNadir,
                    evaluable ? Enums.Evaluable.EVALUABLE : Enums.Evaluable.NOT_EVALUABLE,
                    comparable, new ArrayList<>(flags), contributing, basis, sig);
            if (upsertMetric(subjectId, computed, seq)) changed++;
        }
        linkRepo.finishRun(seq, changed);
        return changed;
    }

    /** 变化百分比：(current-ref)/ref*100，一位小数；参照为空或 0 时不可计算返回 null。 */
    private BigDecimal pct(BigDecimal current, BigDecimal ref) {
        if (current == null || ref == null || ref.signum() == 0) return null;
        return current.subtract(ref).multiply(BigDecimal.valueOf(100))
                .divide(ref, 1, RoundingMode.HALF_UP);
    }

    private Map<String, Object> buildBasis(long seq, ReadingRepo.ReadingRow reading,
                                           List<LesionLinkRepo.LinkRow> links,
                                           List<ReadingRepo.MeasurementRow> formal,
                                           List<ReadingRepo.MeasurementRow> newOnes,
                                           List<ReadingRepo.MeasurementRow> nonMeasurable,
                                           List<ReadingRepo.MeasurementRow> unconfirmed,
                                           ImagingRepo.ScanRow scan, boolean baseline, String sig) {
        var basis = new LinkedHashMap<String, Object>();
        basis.put("mappingRunSeq", seq);
        basis.put("ruleVersion", Enums.LESION_RULE_VERSION);
        basis.put("readingSessionId", reading.id());
        basis.put("readingVersion", reading.readingVersion());
        basis.put("scanId", scan.id());
        basis.put("scanKey", scan.scanKey());
        basis.put("protocolStatus", scan.protocolStatus());
        basis.put("comparableToBaseline", scan.comparableToBaseline());
        basis.put("baselineScan", baseline);
        basis.put("evidenceSignature", sig);
        basis.put("confirmedLinkIds", links.stream().map(LesionLinkRepo.LinkRow::id).toList());
        basis.put("summedMeasurementIds", formal.stream().map(ReadingRepo.MeasurementRow::id).toList());
        basis.put("newLesionMeasurementIds", newOnes.stream().map(ReadingRepo.MeasurementRow::id).toList());
        basis.put("nonMeasurableIds", nonMeasurable.stream().map(ReadingRepo.MeasurementRow::id).toList());
        basis.put("unconfirmedExcludedIds", unconfirmed.stream().map(ReadingRepo.MeasurementRow::id).toList());
        return basis;
    }

    private String sigOf(List<LesionLinkRepo.LinkRow> links, List<ReadingRepo.MeasurementRow> meas,
                         int readingVersion, String protocolStatus, boolean comparable) {
        String linkSig = links.stream().map(l -> l.id() + ":" + l.relationType()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String measSig = meas.stream()
                .map(m -> m.id() + "=" + (m.measurable() ? String.valueOf(m.diameter()) : "NA"))
                .sorted().collect(java.util.stream.Collectors.joining(","));
        return "v" + readingVersion + "|" + protocolStatus + "|" + comparable + "|" + linkSig + "|" + measSig;
    }

    private boolean upsertMetric(long subjectId, Computed c, long seq) {
        String flagsJson = json(c.flags());
        String contributingJson = json(c.contributing());
        String basisJson = json(c.basis());
        var existing = metricRepo.findForScan(subjectId, c.scanId());
        if (existing.isEmpty()) {
            metricRepo.insertMetric(new LesionMetricRepo.MetricRow(0, subjectId, c.scanId(), c.readingId(),
                    c.visitCode(), c.scanDate(), 1, seq, c.sum(), c.baselineSum(), c.nadir(),
                    c.pctBaseline(), c.pctNadir(), c.status(), c.comparable(),
                    flagsJson, contributingJson, basisJson, null));
            return true;
        }
        var ex = existing.get();
        // jsonb 经 PostgreSQL 规范化后文本格式（空格/引号）与 Jackson 紧凑串不同，
        // 故结构化字段先解析再紧凑序列化后比较，避免无内容变化也误判版本跃迁。
        boolean changed = !Objects.equals(ex.sumDiameters(), c.sum())
                || !Objects.equals(ex.baselineSum(), c.baselineSum())
                || !Objects.equals(ex.nadirSum(), c.nadir())
                || !Objects.equals(ex.pctChangeBaseline(), c.pctBaseline())
                || !Objects.equals(ex.pctChangeNadir(), c.pctNadir())
                || !Objects.equals(ex.evaluableStatus(), c.status())
                || ex.comparable() != c.comparable()
                || !Objects.equals(canonical(ex.flagsJson()), flagsJson)
                || !Objects.equals(canonical(ex.contributingJson()), contributingJson)
                || !Objects.equals(readSig(ex.basisJson()), c.sig());
        if (!changed) return false;
        metricRepo.updateMetric(new LesionMetricRepo.MetricRow(ex.id(), subjectId, c.scanId(), c.readingId(),
                c.visitCode(), c.scanDate(), ex.version() + 1, seq, c.sum(), c.baselineSum(), c.nadir(),
                c.pctBaseline(), c.pctNadir(), c.status(), c.comparable(),
                flagsJson, contributingJson, basisJson, null));
        return true;
    }

    /** 把（可能已被 PostgreSQL 规范化的）jsonb 文本重写为紧凑规范串，用于内容相等比较。 */
    private String canonical(String storedJson) {
        try {
            if (storedJson == null) return null;
            return om.writeValueAsString(om.readValue(storedJson, Object.class));
        } catch (Exception e) {
            return storedJson;
        }
    }

    @SuppressWarnings("unchecked")
    private String readSig(String basisJson) {
        try {
            if (basisJson == null) return null;
            Map<String, Object> b = om.readValue(basisJson, Map.class);
            return (String) b.get("evidenceSignature");
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    public List<LesionDtos.MetricOut> metricsOfSubject(long subjectId) {
        String subjectKey = masterRepo.findSubjectById(subjectId)
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        return metricRepo.metricsOfSubject(subjectId).stream().map(r -> toOut(r, subjectKey)).toList();
    }

    public LesionDtos.MetricOut toOut(LesionMetricRepo.MetricRow r, String subjectKey) {
        List<String> flags = parseList(r.flagsJson());
        List<Long> contributing = parseLongList(r.contributingJson());
        Map<String, Object> basis = parseMap(r.basisJson());
        return new LesionDtos.MetricOut(r.id(), r.subjectId(), subjectKey, r.scanId(),
                imagingRepo.findScanById(r.scanId()).map(ImagingRepo.ScanRow::scanKey).orElse("?"),
                r.readingSessionId(), r.visitCode(), r.scanDate(), r.version(), r.seq(),
                r.sumDiameters(), r.baselineSum(), r.nadirSum(), r.pctChangeBaseline(), r.pctChangeNadir(),
                r.evaluableStatus(), r.comparable(), flags, contributing, basis);
    }

    /** 病灶时间线：测量节点 + 确认映射给出的父身份与关系，未确认测量以 relation=UNMAPPED 单列。 */
    public LesionDtos.TimelineOut timeline(long subjectId) {
        String subjectKey = masterRepo.findSubjectById(subjectId)
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        var lesions = imagingRepo.lesionsOfSubject(subjectId);
        Map<Long, ImagingRepo.LesionRow> lesionById = new LinkedHashMap<>();
        lesions.forEach(l -> lesionById.put(l.id(), l));
        var links = linkRepo.linksOfSubject(subjectId);
        Map<Long, LesionLinkRepo.LinkRow> linkByMeas = new LinkedHashMap<>();
        links.forEach(l -> linkByMeas.put(l.toMeasurementId(), l));

        var nodes = new ArrayList<LesionDtos.TimelineNodeOut>();
        long baselineScanId = imagingRepo.scansOfSubject(subjectId).stream()
                .findFirst().map(ImagingRepo.ScanRow::id).orElse(-1L);
        for (var scan : imagingRepo.scansOfSubject(subjectId)) {
            var reading = readingRepo.latestReadingOfScan(scan.id()).orElse(null);
            if (reading == null || !Enums.Reading.FINAL.equals(reading.status())) continue;
            for (var m : readingRepo.currentMeasurementsOfScan(subjectId, scan.id())) {
                var lesion = lesionById.get(m.lesionId());
                var link = linkByMeas.get(m.id());
                String relation = link == null
                        ? (scan.id() == baselineScanId ? "BASELINE" : "UNMAPPED")
                        : link.relationType();
                nodes.add(new LesionDtos.TimelineNodeOut(
                        m.lesionId(), lesion == null ? "?" : lesion.lesionKey(),
                        lesion == null ? "?" : lesion.bodySite(), relation,
                        scan.id(), scan.scanKey(), scan.visitCode(), scan.scanDate(),
                        m.id(), m.diameter(), m.measurable(), m.nonMeasurableReason(), m.isNew(),
                        link == null ? null : link.fromLesionId(),
                        link == null ? null : link.confidence()));
            }
        }
        return new LesionDtos.TimelineOut(subjectKey, nodes);
    }

    public LesionMetricRepo.MetricRow requireMetric(long metricId) {
        return metricRepo.findById(metricId)
                .orElseThrow(() -> new NotFoundException("指标不存在: " + metricId));
    }

    /** 证据链：指标当前态 + 全部版本历史 + 所用扫描/阅片/确认映射/测量明细。 */
    public Map<String, Object> traceMetric(long metricId) {
        var row = requireMetric(metricId);
        String subjectKey = masterRepo.findSubjectById(row.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        var out = new LinkedHashMap<String, Object>();
        out.put("metric", toOut(row, subjectKey));
        out.put("history", metricRepo.historyOf(metricId).stream().map(this::parseHistoryJson).toList());

        @SuppressWarnings("unchecked")
        Map<String, Object> basis = parseMap(row.basisJson());
        var linkIds = ((List<?>) basis.getOrDefault("confirmedLinkIds", List.of())).stream()
                .map(x -> ((Number) x).longValue()).toList();
        var linkEvidence = new ArrayList<Map<String, Object>>();
        for (var l : linkRepo.allLinks().stream().filter(l -> linkIds.contains(l.id())).toList()) {
            var e = new LinkedHashMap<String, Object>();
            e.put("linkId", l.id());
            e.put("relationType", l.relationType());
            e.put("confidence", l.confidence());
            e.put("decidedBy", l.decidedBy());
            e.put("fromLesionKey", l.fromLesionId() == null ? null
                    : imagingRepo.findLesionById(l.fromLesionId()).map(ImagingRepo.LesionRow::lesionKey).orElse("?"));
            e.put("toLesionKey", imagingRepo.findLesionById(l.toLesionId())
                    .map(ImagingRepo.LesionRow::lesionKey).orElse("?"));
            e.put("rationale", l.rationale());
            linkEvidence.add(e);
        }
        var measEvidence = new ArrayList<Map<String, Object>>();
        for (var m : readingRepo.measurementsOfReading(row.readingSessionId())) {
            var e = new LinkedHashMap<String, Object>();
            e.put("measurementId", m.id());
            e.put("lesionKey", imagingRepo.findLesionById(m.lesionId())
                    .map(ImagingRepo.LesionRow::lesionKey).orElse("?"));
            e.put("isNew", m.isNew());
            e.put("measurable", m.measurable());
            e.put("diameter", m.diameter());
            e.put("nonMeasurableReason", m.nonMeasurableReason());
            e.put("contourRef", m.contourRef());
            e.put("evidenceRef", m.evidenceRef());
            measEvidence.add(e);
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scan", imagingRepo.findScanById(row.scanId())
                .map(s -> Map.of("scanKey", s.scanKey(), "visitCode", s.visitCode(), "scanDate", s.scanDate(),
                        "protocolStatus", s.protocolStatus(), "comparableToBaseline", s.comparableToBaseline()))
                .orElse(Map.of()));
        evidence.put("links", linkEvidence);
        evidence.put("measurements", measEvidence);
        evidence.put("basis", basis);
        out.put("evidence", evidence);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseList(String json) {
        try { return json == null ? List.of() : om.readValue(json, List.class); }
        catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseLongList(String json) {
        try {
            if (json == null) return List.of();
            List<Object> raw = om.readValue(json, List.class);
            return raw.stream().map(x -> ((Number) x).longValue()).toList();
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        try { return json == null ? Map.of() : om.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    /** 历史行中的 jsonb 已 ::text，解析回结构化对象以便 API 直接消费。 */
    private Map<String, Object> parseHistoryJson(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        row.forEach((k, v) -> {
            if ((k.equals("flags") || k.equals("contributing_lesion_ids") || k.equals("basis")) && v != null) {
                out.put(k, parseMap(v.toString()).isEmpty() ? parseList(v.toString()) : parseMap(v.toString()));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
