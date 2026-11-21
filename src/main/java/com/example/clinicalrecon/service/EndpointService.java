package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.EndpointRepo;
import com.example.clinicalrecon.repo.ImagingRepo;
import com.example.clinicalrecon.repo.LesionLinkRepo;
import com.example.clinicalrecon.repo.LesionMetricRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 疗效数据集：物化受试者×扫描的纵向测量结果，记录所用扫描、阅片版本与映射依据，
 * 供统计团队派生终点（本服务不做医学裁定）。
 * 物化行锚定指标版本；更正阅片/映射只产生受影响受试者的新指标版本，旧数据集据映射序号回放复算。
 */
@Service
public class EndpointService {
    private final EndpointRepo endpointRepo;
    private final LesionMetricRepo metricRepo;
    private final LesionLinkRepo linkRepo;
    private final ImagingRepo imagingRepo;
    private final MasterDataRepo masterRepo;
    private final ObjectMapper om = new ObjectMapper();

    public EndpointService(EndpointRepo endpointRepo, LesionMetricRepo metricRepo, LesionLinkRepo linkRepo,
                           ImagingRepo imagingRepo, MasterDataRepo masterRepo) {
        this.endpointRepo = endpointRepo;
        this.metricRepo = metricRepo;
        this.linkRepo = linkRepo;
        this.imagingRepo = imagingRepo;
        this.masterRepo = masterRepo;
    }

    @Transactional
    public LesionDtos.EndpointDatasetOut create(LesionDtos.EndpointDatasetIn in) {
        if (endpointRepo.findByName(in.name()).isPresent()) {
            throw new BadRequestException("疗效数据集名称已存在: " + in.name());
        }
        boolean includeNotEvaluable = in.includeNotEvaluable() != null && in.includeNotEvaluable();
        boolean requireComparable = in.requireComparable() == null || in.requireComparable();

        var scopeSubjectIds = new LinkedHashSet<Long>();
        if (in.subjectKeys() != null) {
            for (var key : in.subjectKeys()) {
                masterRepo.findSubjectByKey(key).ifPresent(s -> scopeSubjectIds.add(s.id()));
            }
        }
        long seq = linkRepo.currentSeq();
        var gate = new LinkedHashMap<String, Object>();
        gate.put("includeNotEvaluable", includeNotEvaluable);
        gate.put("requireComparable", requireComparable);

        var allMetrics = metricRepo.allMetrics().stream()
                .filter(m -> scopeSubjectIds.isEmpty() || scopeSubjectIds.contains(m.subjectId()))
                .toList();
        var included = allMetrics.stream()
                .filter(m -> includeNotEvaluable
                        || Enums.Evaluable.EVALUABLE.equals(m.evaluableStatus()))
                .filter(m -> !requireComparable || m.comparable())
                .toList();

        long datasetId;
        try {
            datasetId = endpointRepo.insertDataset(in.name(), UserContext.user(), seq,
                    Enums.LESION_RULE_VERSION, om.writeValueAsString(gate), included.size(),
                    om.writeValueAsString(scopeSubjectIds.stream()
                            .map(id -> masterRepo.findSubjectById(id).map(MasterDataRepo.SubjectRow::subjectKey).orElse("?"))
                            .toList()));
        } catch (Exception e) {
            throw new BadRequestException("疗效数据集创建失败: " + e.getMessage());
        }

        for (var m : included) {
            Map<String, Object> evidence = buildEvidence(m);
            endpointRepo.insertRow(datasetId, m.subjectId(), m.scanId(), m.visitCode(), m.scanDate(),
                    m.id(), m.version(), m.sumDiameters(), m.baselineSum(), m.nadirSum(),
                    m.pctChangeBaseline(), m.pctChangeNadir(), m.evaluableStatus(), m.comparable(),
                    m.flagsJson(), json(evidence));
        }
        var scopeKeys = scopeSubjectIds.stream()
                .map(id -> masterRepo.findSubjectById(id).map(MasterDataRepo.SubjectRow::subjectKey).orElse("?"))
                .toList();
        return new LesionDtos.EndpointDatasetOut(datasetId, in.name(), null, UserContext.user(), seq,
                Enums.LESION_RULE_VERSION, gate, included.size(), scopeKeys);
    }

    private Map<String, Object> buildEvidence(LesionMetricRepo.MetricRow m) {
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = parse(m.basisJson(), Map.class);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("scanId", m.scanId());
        evidence.put("scanKey", imagingRepo.findScanById(m.scanId()).map(ImagingRepo.ScanRow::scanKey).orElse("?"));
        evidence.put("readingSessionId", m.readingSessionId());
        evidence.put("readingVersion", basis.get("readingVersion"));
        evidence.put("metricVersion", m.version());
        evidence.put("mappingRunSeq", m.seq());
        evidence.put("ruleVersion", Enums.LESION_RULE_VERSION);
        evidence.put("confirmedLinkIds", basis.getOrDefault("confirmedLinkIds", List.of()));
        evidence.put("summedMeasurementIds", basis.getOrDefault("summedMeasurementIds", List.of()));
        evidence.put("newLesionMeasurementIds", basis.getOrDefault("newLesionMeasurementIds", List.of()));
        evidence.put("nonMeasurableIds", basis.getOrDefault("nonMeasurableIds", List.of()));
        evidence.put("unconfirmedExcludedIds", basis.getOrDefault("unconfirmedExcludedIds", List.of()));
        evidence.put("evidenceSignature", basis.get("evidenceSignature"));
        return evidence;
    }

    public List<LesionDtos.EndpointDatasetOut> listOut() {
        return endpointRepo.list().stream().map(this::toDatasetOut).toList();
    }

    private LesionDtos.EndpointDatasetOut toDatasetOut(EndpointRepo.DatasetRow r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = parse(r.gateJson(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> scope = parse(r.scopeJson(), List.class);
        return new LesionDtos.EndpointDatasetOut(r.id(), r.name(),
                r.createdAt() == null ? null : r.createdAt().toLocalDate(), r.createdBy(), r.seq(),
                r.ruleVersion(), gate, r.rowCount(), scope);
    }

    public List<LesionDtos.EndpointRowOut> rows(long datasetId) {
        requireDataset(datasetId);
        var out = new ArrayList<LesionDtos.EndpointRowOut>();
        for (var r : endpointRepo.rowsOf(datasetId)) {
            out.add(toRowOut(r));
        }
        return out;
    }

    /**
     * 复算验证：以数据集记录的映射序号为界，从追加式指标历史重放同一门槛，
     * 与物化行逐条比对；并列出创建后指标版本已跃迁的受影响受试者（旧终点仍可回放）。
     */
    @Transactional
    public LesionDtos.EndpointVerifyOut verify(long datasetId) {
        var ds = requireDataset(datasetId);
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = parse(ds.gateJson(), Map.class);
        boolean includeNotEvaluable = Boolean.TRUE.equals(gate.get("includeNotEvaluable"));
        boolean requireComparable = !Boolean.FALSE.equals(gate.get("requireComparable"));

        // 截至数据集序号，每个指标的最新历史版本与状态
        var replay = new LinkedHashMap<Long, Map<String, Object>>();
        for (var h : metricRepo.historyAsOf(ds.seq())) {
            replay.put(((Number) h.get("metric_id")).longValue(), h);
        }

        var materialized = endpointRepo.rawRowsOf(datasetId);
        var changedSubjects = new LinkedHashSet<String>();
        var extraInReplay = new ArrayList<Long>();
        boolean reproducible = true;

        for (var row : materialized) {
            long metricId = ((Number) row.get("metric_id")).longValue();
            int anchoredVersion = ((Number) row.get("metric_version")).intValue();
            var h = replay.get(metricId);
            if (h == null || ((Number) h.get("version")).intValue() != anchoredVersion) {
                reproducible = false;
            }
            // 创建后该指标是否已有更新版本（受影响受试者，需要新数据集版本）
            var current = metricRepo.findById(metricId);
            if (current.isPresent() && current.get().version() > anchoredVersion) {
                String key = masterRepo.findSubjectById(current.get().subjectId())
                        .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
                changedSubjects.add(key);
            }
        }

        // 重放门槛应得的指标集合是否与物化集合一致
        var replayEligible = new LinkedHashSet<Long>();
        for (var e : replay.entrySet()) {
            var h = e.getValue();
            String status = (String) h.get("evaluable_status");
            boolean comparable = Boolean.TRUE.equals(h.get("comparable"));
            boolean pass = (includeNotEvaluable || Enums.Evaluable.EVALUABLE.equals(status))
                    && (!requireComparable || comparable);
            if (pass) replayEligible.add(e.getKey());
        }
        var materializedMetricIds = materialized.stream()
                .map(r -> ((Number) r.get("metric_id")).longValue())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (var metricId : replayEligible) {
            if (!materializedMetricIds.contains(metricId)) {
                extraInReplay.add(metricId);
                reproducible = false;
            }
        }

        String message = reproducible
                ? "复算一致：按映射序号 " + ds.seq() + " 重放门槛与物化行完全相同；旧终点可回放"
                : "复算存在差异，请核对指标历史与物化行";
        return new LesionDtos.EndpointVerifyOut(datasetId, reproducible,
                new ArrayList<>(changedSubjects), extraInReplay, message);
    }

    private EndpointRepo.DatasetRow requireDataset(long id) {
        return endpointRepo.findById(id)
                .orElseThrow(() -> new com.example.clinicalrecon.config.ApiExceptionHandler
                        .NotFoundException("疗效数据集不存在: " + id));
    }

    @SuppressWarnings("unchecked")
    private LesionDtos.EndpointRowOut toRowOut(Map<String, Object> r) {
        long subjectId = ((Number) r.get("subject_id")).longValue();
        String subjectKey = (String) r.get("subject_key");
        return new LesionDtos.EndpointRowOut(
                ((Number) r.get("id")).longValue(), ((Number) r.get("dataset_id")).longValue(),
                subjectId, subjectKey, ((Number) r.get("scan_id")).longValue(), (String) r.get("scan_key"),
                (String) r.get("visit_code"),
                r.get("scan_date") instanceof java.sql.Date d ? d.toLocalDate() : (java.time.LocalDate) r.get("scan_date"),
                ((Number) r.get("metric_id")).longValue(), ((Number) r.get("metric_version")).intValue(),
                (java.math.BigDecimal) r.get("sum_diameters"), (java.math.BigDecimal) r.get("baseline_sum"),
                (java.math.BigDecimal) r.get("nadir_sum"), (java.math.BigDecimal) r.get("pct_change_baseline"),
                (java.math.BigDecimal) r.get("pct_change_nadir"), (String) r.get("evaluable_status"),
                Boolean.TRUE.equals(r.get("comparable")), parse((String) r.get("flags"), List.class),
                parse((String) r.get("evidence"), Map.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T parse(String json, Class<T> type) {
        try {
            if (json == null) {
                return type == List.class ? (T) List.of() : (T) Map.of();
            }
            return om.readValue(json, type);
        } catch (Exception e) {
            return type == List.class ? (T) List.of() : (T) Map.of();
        }
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
