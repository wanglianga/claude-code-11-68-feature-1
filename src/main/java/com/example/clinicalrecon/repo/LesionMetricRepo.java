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

/** 受试者×扫描 指标当前态与追加式版本历史（旧终点可回放的基础）。 */
@Repository
public class LesionMetricRepo {
    private final JdbcTemplate jdbc;

    public LesionMetricRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record MetricRow(long id, long subjectId, long scanId, long readingSessionId, String visitCode,
                            LocalDate scanDate, int version, long seq,
                            BigDecimal sumDiameters, BigDecimal baselineSum, BigDecimal nadirSum,
                            BigDecimal pctChangeBaseline, BigDecimal pctChangeNadir,
                            String evaluableStatus, boolean comparable, String flagsJson,
                            String contributingJson, String basisJson, OffsetDateTime updatedAt) {}

    public static final RowMapper<MetricRow> METRIC_MAPPER = (rs, i) -> new MetricRow(
            rs.getLong("id"), rs.getLong("subject_id"), rs.getLong("scan_id"),
            rs.getLong("reading_session_id"), rs.getString("visit_code"),
            rs.getDate("scan_date").toLocalDate(), rs.getInt("version"), rs.getLong("seq"),
            rs.getBigDecimal("sum_diameters"), rs.getBigDecimal("baseline_sum"), rs.getBigDecimal("nadir_sum"),
            rs.getBigDecimal("pct_change_baseline"), rs.getBigDecimal("pct_change_nadir"),
            rs.getString("evaluable_status"), rs.getBoolean("comparable"),
            rs.getString("flags"), rs.getString("contributing_lesion_ids"), rs.getString("basis"),
            rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    public Optional<MetricRow> findForScan(long subjectId, long scanId) {
        return jdbc.query("select * from lesion_metric where subject_id=? and scan_id=?",
                METRIC_MAPPER, subjectId, scanId).stream().findFirst();
    }

    public Optional<MetricRow> findById(long id) {
        return jdbc.query("select * from lesion_metric where id=?", METRIC_MAPPER, id).stream().findFirst();
    }

    public List<MetricRow> metricsOfSubject(long subjectId) {
        return jdbc.query("select * from lesion_metric where subject_id=? order by scan_date, id",
                METRIC_MAPPER, subjectId);
    }

    public List<MetricRow> allMetrics() {
        return jdbc.query("select * from lesion_metric order by subject_id, scan_date, id", METRIC_MAPPER);
    }

    public long insertMetric(MetricRow m) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into lesion_metric(subject_id, scan_id, reading_session_id, visit_code, scan_date,
                        version, seq, sum_diameters, baseline_sum, nadir_sum, pct_change_baseline,
                        pct_change_nadir, evaluable_status, comparable, flags, contributing_lesion_ids, basis)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)""", new String[]{"id"});
            fillStatement(ps, m);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        insertHistory(id, m);
        return id;
    }

    public void updateMetric(MetricRow m) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    update lesion_metric set reading_session_id=?, version=?, seq=?, sum_diameters=?,
                        baseline_sum=?, nadir_sum=?, pct_change_baseline=?, pct_change_nadir=?,
                        evaluable_status=?, comparable=?, flags=?::jsonb, contributing_lesion_ids=?::jsonb,
                        basis=?::jsonb, updated_at=now()
                    where id=?""");
            ps.setLong(1, m.readingSessionId());
            ps.setInt(2, m.version());
            ps.setLong(3, m.seq());
            ps.setBigDecimal(4, m.sumDiameters());
            ps.setBigDecimal(5, m.baselineSum());
            ps.setBigDecimal(6, m.nadirSum());
            ps.setBigDecimal(7, m.pctChangeBaseline());
            ps.setBigDecimal(8, m.pctChangeNadir());
            ps.setString(9, m.evaluableStatus());
            ps.setBoolean(10, m.comparable());
            ps.setString(11, m.flagsJson());
            ps.setString(12, m.contributingJson());
            ps.setString(13, m.basisJson());
            ps.setLong(14, m.id());
            return ps;
        });
        insertHistory(m.id(), m);
    }

    private void fillStatement(PreparedStatement ps, MetricRow m) throws java.sql.SQLException {
        ps.setLong(1, m.subjectId());
        ps.setLong(2, m.scanId());
        ps.setLong(3, m.readingSessionId());
        ps.setString(4, m.visitCode());
        ps.setDate(5, java.sql.Date.valueOf(m.scanDate()));
        ps.setInt(6, m.version());
        ps.setLong(7, m.seq());
        ps.setBigDecimal(8, m.sumDiameters());
        ps.setBigDecimal(9, m.baselineSum());
        ps.setBigDecimal(10, m.nadirSum());
        ps.setBigDecimal(11, m.pctChangeBaseline());
        ps.setBigDecimal(12, m.pctChangeNadir());
        ps.setString(13, m.evaluableStatus());
        ps.setBoolean(14, m.comparable());
        ps.setString(15, m.flagsJson());
        ps.setString(16, m.contributingJson());
        ps.setString(17, m.basisJson());
    }

    private void insertHistory(long metricId, MetricRow m) {
        jdbc.update("""
                insert into lesion_metric_history(metric_id, version, seq, sum_diameters, baseline_sum, nadir_sum,
                    pct_change_baseline, pct_change_nadir, evaluable_status, comparable, flags,
                    contributing_lesion_ids, basis)
                values(?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                on conflict (metric_id, version) do nothing""",
                metricId, m.version(), m.seq(), m.sumDiameters(), m.baselineSum(), m.nadirSum(),
                m.pctChangeBaseline(), m.pctChangeNadir(), m.evaluableStatus(), m.comparable(),
                m.flagsJson(), m.contributingJson(), m.basisJson());
    }

    public List<Map<String, Object>> historyOf(long metricId) {
        return jdbc.queryForList("""
                select version, seq, sum_diameters, baseline_sum, nadir_sum, pct_change_baseline,
                       pct_change_nadir, evaluable_status, comparable,
                       flags::text as flags, contributing_lesion_ids::text as contributing_lesion_ids,
                       basis::text as basis, created_at
                from lesion_metric_history where metric_id=? order by version""", metricId);
    }

    /** 疗效数据集复算：截至某映射序号，每条指标的最新历史版本 */
    public List<Map<String, Object>> historyAsOf(long seq) {
        return jdbc.queryForList("""
                select h.metric_id, h.version, h.sum_diameters, h.pct_change_baseline,
                       h.pct_change_nadir, h.evaluable_status, h.comparable,
                       h.flags::text as flags, h.contributing_lesion_ids::text as contributing_lesion_ids,
                       h.basis::text as basis
                from lesion_metric_history h
                join (select metric_id, max(version) v from lesion_metric_history
                      where seq <= ? group by metric_id) latest
                  on latest.metric_id = h.metric_id and latest.v = h.version""", seq);
    }
}
