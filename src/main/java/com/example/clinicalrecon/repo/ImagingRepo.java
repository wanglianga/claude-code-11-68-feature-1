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

/** 影像检查、影像序列与靶病灶登记数据访问。 */
@Repository
public class ImagingRepo {
    private final JdbcTemplate jdbc;

    public ImagingRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ScanRow(long id, long subjectId, String centerId, String scanKey, String visitCode,
                          LocalDate scanDate, String modality, String scannerModel, BigDecimal sliceThickness,
                          String contrastSeries, String protocolStatus, String protocolReason,
                          boolean comparableToBaseline, OffsetDateTime receivedAt, String createdBy) {}

    public record LesionRow(long id, long subjectId, String lesionKey, String bodySite, String measurementAxis,
                            long baselineScanId, Long baselineSeriesId, String origin, String status,
                            String note, String registeredBy, OffsetDateTime registeredAt) {}

    public static final RowMapper<ScanRow> SCAN_MAPPER = (rs, i) -> new ScanRow(
            rs.getLong("id"), rs.getLong("subject_id"), rs.getString("center_id"), rs.getString("scan_key"),
            rs.getString("visit_code"), rs.getDate("scan_date").toLocalDate(), rs.getString("modality"),
            rs.getString("scanner_model"), rs.getBigDecimal("slice_thickness"), rs.getString("contrast_series"),
            rs.getString("protocol_status"), rs.getString("protocol_reason"),
            rs.getBoolean("comparable_to_baseline"),
            rs.getTimestamp("received_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getString("created_by"));

    public static final RowMapper<LesionRow> LESION_MAPPER = (rs, i) -> new LesionRow(
            rs.getLong("id"), rs.getLong("subject_id"), rs.getString("lesion_key"), rs.getString("body_site"),
            rs.getString("measurement_axis"), rs.getLong("baseline_scan_id"),
            rs.getObject("baseline_series_id") == null ? null : rs.getLong("baseline_series_id"),
            rs.getString("origin"), rs.getString("status"), rs.getString("note"),
            rs.getString("registered_by"),
            rs.getTimestamp("registered_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    // ---------- 检查 ----------
    public long insertScan(long subjectId, String centerId, String scanKey, String visitCode, LocalDate scanDate,
                           String modality, String scannerModel, BigDecimal sliceThickness, String contrastSeries,
                           String protocolStatus, String protocolReason, boolean comparable, String createdBy) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into imaging_scan(subject_id, center_id, scan_key, visit_code, scan_date, modality,
                        scanner_model, slice_thickness, contrast_series, protocol_status, protocol_reason,
                        comparable_to_baseline, created_by)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, subjectId);
            ps.setString(2, centerId);
            ps.setString(3, scanKey);
            ps.setString(4, visitCode);
            ps.setDate(5, java.sql.Date.valueOf(scanDate));
            ps.setString(6, modality);
            ps.setString(7, scannerModel);
            ps.setBigDecimal(8, sliceThickness);
            ps.setString(9, contrastSeries);
            ps.setString(10, protocolStatus);
            ps.setString(11, protocolReason);
            ps.setBoolean(12, comparable);
            ps.setString(13, createdBy);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<ScanRow> findScan(long subjectId, String scanKey) {
        return jdbc.query("select * from imaging_scan where subject_id=? and scan_key=?",
                SCAN_MAPPER, subjectId, scanKey).stream().findFirst();
    }

    public Optional<ScanRow> findScanById(long id) {
        return jdbc.query("select * from imaging_scan where id=?", SCAN_MAPPER, id).stream().findFirst();
    }

    public List<ScanRow> scansOfSubject(long subjectId) {
        return jdbc.query("select * from imaging_scan where subject_id=? order by scan_date, id",
                SCAN_MAPPER, subjectId);
    }

    public List<ScanRow> allScans() {
        return jdbc.query("select * from imaging_scan order by subject_id, scan_date, id", SCAN_MAPPER);
    }

    // ---------- 序列 ----------
    public long insertSeries(long scanId, String seriesKey, String modality, String sequenceLabel,
                             BigDecimal sliceThickness) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into imaging_series(scan_id, series_key, modality, sequence_label, slice_thickness)
                    values(?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, scanId);
            ps.setString(2, seriesKey);
            ps.setString(3, modality);
            ps.setString(4, sequenceLabel);
            ps.setBigDecimal(5, sliceThickness);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<Long> findSeriesId(long scanId, String seriesKey) {
        if (seriesKey == null) return Optional.empty();
        return jdbc.query("select id from imaging_series where scan_id=? and series_key=?",
                (rs, i) -> rs.getLong(1), scanId, seriesKey).stream().findFirst();
    }

    public List<Map<String, Object>> seriesOfScan(long scanId) {
        return jdbc.queryForList("""
                select id, scan_id as "scanId", series_key as "seriesKey", modality,
                       sequence_label as "sequenceLabel", slice_thickness as "sliceThickness"
                from imaging_series where scan_id=? order by id""", scanId);
    }

    // ---------- 靶病灶 ----------
    public long insertLesion(long subjectId, String lesionKey, String bodySite, String axis,
                             long baselineScanId, Long baselineSeriesId, String origin, String note,
                             String registeredBy) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into target_lesion(subject_id, lesion_key, body_site, measurement_axis,
                        baseline_scan_id, baseline_series_id, origin, note, registered_by)
                    values(?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, subjectId);
            ps.setString(2, lesionKey);
            ps.setString(3, bodySite);
            ps.setString(4, axis);
            ps.setLong(5, baselineScanId);
            ps.setObject(6, baselineSeriesId);
            ps.setString(7, origin);
            ps.setString(8, note);
            ps.setString(9, registeredBy);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public Optional<LesionRow> findLesionByKey(String lesionKey) {
        return jdbc.query("select * from target_lesion where lesion_key=?", LESION_MAPPER, lesionKey)
                .stream().findFirst();
    }

    public Optional<LesionRow> findLesionById(long id) {
        return jdbc.query("select * from target_lesion where id=?", LESION_MAPPER, id).stream().findFirst();
    }

    public List<LesionRow> lesionsOfSubject(long subjectId) {
        return jdbc.query("select * from target_lesion where subject_id=? order by id", LESION_MAPPER, subjectId);
    }

    public List<LesionRow> baselineLesions(long subjectId) {
        return jdbc.query("""
                select l.* from target_lesion l
                join imaging_scan s on s.id = l.baseline_scan_id
                where l.subject_id=? and l.origin='BASELINE' order by l.id""", LESION_MAPPER, subjectId);
    }

    public void updateLesionStatus(long lesionId, String status) {
        jdbc.update("update target_lesion set status=? where id=?", status, lesionId);
    }
}
