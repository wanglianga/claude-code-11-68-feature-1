package com.example.clinicalrecon.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 疗效数据集（头表 + 物化行），记录扫描/阅片版本/映射依据，物化后不可变、可复算。 */
@Repository
public class EndpointRepo {
    private final JdbcTemplate jdbc;

    public EndpointRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record DatasetRow(long id, String name, OffsetDateTime createdAt, String createdBy, long seq,
                             String ruleVersion, String gateJson, int rowCount, String scopeJson) {}

    public static final RowMapper<DatasetRow> DATASET_MAPPER = (rs, i) -> new DatasetRow(
            rs.getLong("id"), rs.getString("name"),
            rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getString("created_by"), rs.getLong("seq"), rs.getString("rule_version"),
            rs.getString("gate"), rs.getInt("row_count"), rs.getString("scope_subject_ids"));

    public long insertDataset(String name, String createdBy, long seq, String ruleVersion, String gateJson,
                              int rowCount, String scopeJson) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into endpoint_dataset(name, created_by, seq, rule_version, gate, row_count, scope_subject_ids)
                    values(?,?,?,?::text,?::jsonb,?,?::jsonb)""", new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, createdBy);
            ps.setLong(3, seq);
            ps.setString(4, ruleVersion);
            ps.setString(5, gateJson);
            ps.setInt(6, rowCount);
            ps.setString(7, scopeJson);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<DatasetRow> findById(long id) {
        return jdbc.query("select * from endpoint_dataset where id=?", DATASET_MAPPER, id).stream().findFirst();
    }

    public Optional<DatasetRow> findByName(String name) {
        return jdbc.query("select * from endpoint_dataset where name=?", DATASET_MAPPER, name).stream().findFirst();
    }

    public List<DatasetRow> list() {
        return jdbc.query("select * from endpoint_dataset order by id", DATASET_MAPPER);
    }

    public void insertRow(long datasetId, long subjectId, long scanId, String visitCode, LocalDate scanDate,
                          long metricId, int metricVersion, BigDecimal sumDiameters, BigDecimal baselineSum,
                          BigDecimal nadirSum, BigDecimal pctBaseline, BigDecimal pctNadir,
                          String evaluableStatus, boolean comparable, String flagsJson, String evidenceJson) {
        jdbc.update("""
                insert into endpoint_dataset_row(dataset_id, subject_id, scan_id, visit_code, scan_date,
                    metric_id, metric_version, sum_diameters, baseline_sum, nadir_sum, pct_change_baseline,
                    pct_change_nadir, evaluable_status, comparable, flags, evidence)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                datasetId, subjectId, scanId, visitCode, java.sql.Date.valueOf(scanDate), metricId, metricVersion,
                sumDiameters, baselineSum, nadirSum, pctBaseline, pctNadir, evaluableStatus, comparable,
                flagsJson, evidenceJson);
    }

    public List<Map<String, Object>> rowsOf(long datasetId) {
        return jdbc.queryForList("""
                select r.id, r.dataset_id, r.subject_id, s.subject_key, r.scan_id, sc.scan_key,
                       r.visit_code, r.scan_date, r.metric_id, r.metric_version, r.sum_diameters,
                       r.baseline_sum, r.nadir_sum, r.pct_change_baseline, r.pct_change_nadir,
                       r.evaluable_status, r.comparable, r.flags::text as flags, r.evidence::text as evidence
                from endpoint_dataset_row r
                join subject s on s.id = r.subject_id
                join imaging_scan sc on sc.id = r.scan_id
                where r.dataset_id=? order by s.subject_key, r.scan_date, r.id""", datasetId);
    }

    public List<Map<String, Object>> rawRowsOf(long datasetId) {
        return jdbc.queryForList("select * from endpoint_dataset_row where dataset_id=? order by id", datasetId);
    }
}
