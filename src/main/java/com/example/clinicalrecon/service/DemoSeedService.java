package com.example.clinicalrecon.service;

import com.example.clinicalrecon.domain.Dtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示种子：主数据（受试者/中心映射/访视窗口/字段目录）+ 四类来源样例批次。
 * lab-batch-2（检验更正修订）不预置，留给验收流程演示"源更正→质疑重开"。
 */
@Service
public class DemoSeedService {
    private final JdbcTemplate jdbc;
    private final IntakeService intakeService;
    private final ObjectMapper om;

    private static final List<String> SEED_BATCHES = List.of(
            "samples/crf-batch-1.json",
            "samples/lab-batch-1.json",
            "samples/imaging-batch-1.json",
            "samples/monitoring-batch-1.json",
            "samples/crf-batch-2-unmapped.json");

    public DemoSeedService(JdbcTemplate jdbc, IntakeService intakeService, ObjectMapper om) {
        this.jdbc = jdbc;
        this.intakeService = intakeService;
        this.om = om;
    }

    @Transactional
    public void reset() {
        jdbc.execute("""
                truncate table analysis_snapshot_row, analysis_snapshot,
                    query_response, query_item,
                    reconciled_fact_source, reconciled_fact_history, reconciled_fact, reconcile_run,
                    fact_link, link_candidate, raw_fact, source_batch,
                    endpoint_dataset_row, endpoint_dataset,
                    lesion_metric_history, lesion_metric, lesion_mapping_run,
                    lesion_link, lesion_link_candidate, lesion_measurement, reading_session,
                    target_lesion, imaging_series, imaging_scan,
                    field_catalog, visit_schedule, center_subject_map, subject
                restart identity cascade""");
    }

    @Transactional
    public Map<String, Object> seed() {
        reset();
        seedMaster();
        var results = new LinkedHashMap<String, Object>();
        for (var path : SEED_BATCHES) {
            try {
                String json = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                var out = intakeService.ingest(om.readValue(json, Dtos.BatchIn.class));
                results.put(out.batchLabel(), Map.of("mapped", out.mapped(), "unmapped", out.unmapped()));
            } catch (Exception e) {
                throw new IllegalStateException("种子批次加载失败: " + path + " - " + e.getMessage(), e);
            }
        }
        return Map.of("master", "ONCO-2026-01 (3受试者/2中心/3访视/6字段)", "batches", results);
    }

    private void seedMaster() {
        jdbc.update("insert into subject(trial_id, center_id, subject_key, enrolled_date) values(?,?,?,?)",
                "ONCO-2026-01", "C01", "SUBJ-001", java.sql.Date.valueOf("2026-03-01"));
        jdbc.update("insert into subject(trial_id, center_id, subject_key, enrolled_date) values(?,?,?,?)",
                "ONCO-2026-01", "C01", "SUBJ-002", java.sql.Date.valueOf("2026-03-10"));
        jdbc.update("insert into subject(trial_id, center_id, subject_key, enrolled_date) values(?,?,?,?)",
                "ONCO-2026-01", "C02", "SUBJ-003", java.sql.Date.valueOf("2026-04-01"));

        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C01','S-001',id from subject where subject_key='SUBJ-001'""");
        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C01','S-002',id from subject where subject_key='SUBJ-002'""");
        jdbc.update("""
                insert into center_subject_map(center_id, external_subject_id, subject_id)
                select 'C02','S-101',id from subject where subject_key='SUBJ-003'""");

        // 方案访视窗口：V2 与 V3 在第18天相接（窗口跨边界场景）
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V1", 0, -2, 2);
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V2", 14, 13, 18);
        jdbc.update("insert into visit_schedule(trial_id, visit_code, anchor_day, window_start, window_end) values(?,?,?,?,?)",
                "ONCO-2026-01", "V3", 28, 18, 31);

        // 统一字段目录：保留各来源字段编码映射
        insertField("VITAL_SYS_BP", "收缩压", "NUMERIC", "mmHg", true,
                "{\"CRF\":[\"SYSBP\"]}");
        insertField("VITAL_DIA_BP", "舒张压", "NUMERIC", "mmHg", true,
                "{\"CRF\":[\"DIABP\"]}");
        insertField("LAB_ALT", "谷丙转氨酶ALT", "NUMERIC", "U/L", true,
                "{\"LAB\":[\"ALT\"],\"CRF\":[\"ALT\"]}");
        insertField("LAB_AST", "谷草转氨酶AST", "NUMERIC", "U/L", false,
                "{\"LAB\":[\"AST\"]}");
        insertField("TUMOR_LD_SUM", "靶病灶长径和", "NUMERIC", "mm", true,
                "{\"IMAGING\":[\"LD_SUM\"],\"CRF\":[\"TUMSUM\"]}");
        insertField("MON_FINDING", "监查发现", "TEXT", null, false,
                "{\"MONITORING\":[\"FINDING\"]}");
    }

    private void insertField(String key, String label, String type, String unit,
                             boolean required, String mapJson) {
        jdbc.update("""
                insert into field_catalog(field_key, label, value_type, unit, required_for_analysis, source_field_map)
                values(?,?,?,?,?,?::jsonb)""", key, label, type, unit, required, mapJson);
    }
}
