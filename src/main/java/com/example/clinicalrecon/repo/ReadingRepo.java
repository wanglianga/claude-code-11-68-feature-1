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

/** 随访阅片版本与逐病灶测量（含不可测原因、新发现标记）。 */
@Repository
public class ReadingRepo {
    private final JdbcTemplate jdbc;

    public ReadingRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ReadingRow(long id, long subjectId, long scanId, int readingVersion, String reader,
                             LocalDate readAt, String status, String triggerNote, String createdBy,
                             OffsetDateTime createdAt) {}

    public record MeasurementRow(long id, long readingSessionId, long scanId, long subjectId, long lesionId,
                                 boolean isNew, boolean measurable, String nonMeasurableReason,
                                 BigDecimal diameter, String axis, Long seriesId, String contourRef,
                                 String evidenceRef, OffsetDateTime createdAt) {}

    public static final RowMapper<ReadingRow> READING_MAPPER = (rs, i) -> new ReadingRow(
            rs.getLong("id"), rs.getLong("subject_id"), rs.getLong("scan_id"), rs.getInt("reading_version"),
            rs.getString("reader"), rs.getDate("read_at").toLocalDate(), rs.getString("status"),
            rs.getString("trigger_note"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    public static final RowMapper<MeasurementRow> MEAS_MAPPER = (rs, i) -> new MeasurementRow(
            rs.getLong("id"), rs.getLong("reading_session_id"), rs.getLong("scan_id"),
            rs.getLong("subject_id"), rs.getLong("lesion_id"), rs.getBoolean("is_new"),
            rs.getBoolean("measurable"), rs.getString("non_measurable_reason"),
            rs.getBigDecimal("diameter"), rs.getString("axis"),
            rs.getObject("series_id") == null ? null : rs.getLong("series_id"),
            rs.getString("contour_ref"), rs.getString("evidence_ref"),
            rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));

    public long insertReading(long subjectId, long scanId, int version, String reader, LocalDate readAt,
                              String status, String triggerNote, String createdBy) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into reading_session(subject_id, scan_id, reading_version, reader, read_at,
                        status, trigger_note, created_by)
                    values(?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, subjectId);
            ps.setLong(2, scanId);
            ps.setInt(3, version);
            ps.setString(4, reader);
            ps.setDate(5, java.sql.Date.valueOf(readAt));
            ps.setString(6, status);
            ps.setString(7, triggerNote);
            ps.setString(8, createdBy);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 同扫描下一阅片版本号 */
    public int nextReadingVersion(long scanId) {
        Integer v = jdbc.queryForObject(
                "select coalesce(max(reading_version), 0) + 1 from reading_session where scan_id=?",
                Integer.class, scanId);
        return v == null ? 1 : v;
    }

    public void supersedeReadingsOfScan(long scanId) {
        jdbc.update("update reading_session set status='SUPERSEDED' where scan_id=? and status <> 'SUPERSEDED'",
                scanId);
    }

    public Optional<ReadingRow> latestReadingOfScan(long scanId) {
        return jdbc.query("select * from reading_session where scan_id=? order by reading_version desc limit 1",
                READING_MAPPER, scanId).stream().findFirst();
    }

    public Optional<ReadingRow> findReadingById(long id) {
        return jdbc.query("select * from reading_session where id=?", READING_MAPPER, id).stream().findFirst();
    }

    public List<ReadingRow> readingsOfSubject(long subjectId) {
        return jdbc.query("select * from reading_session where subject_id=? order by id", READING_MAPPER, subjectId);
    }

    public List<ReadingRow> allReadings() {
        return jdbc.query("select * from reading_session order by id", READING_MAPPER);
    }

    public long insertMeasurement(MeasurementRow m) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into lesion_measurement(reading_session_id, scan_id, subject_id, lesion_id, is_new,
                        measurable, non_measurable_reason, diameter, axis, series_id, contour_ref, evidence_ref)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)""", new String[]{"id"});
            ps.setLong(1, m.readingSessionId());
            ps.setLong(2, m.scanId());
            ps.setLong(3, m.subjectId());
            ps.setLong(4, m.lesionId());
            ps.setBoolean(5, m.isNew());
            ps.setBoolean(6, m.measurable());
            ps.setString(7, m.nonMeasurableReason());
            ps.setBigDecimal(8, m.diameter());
            ps.setString(9, m.axis());
            ps.setObject(10, m.seriesId());
            ps.setString(11, m.contourRef());
            ps.setString(12, m.evidenceRef());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<MeasurementRow> measurementsOfReading(long readingSessionId) {
        return jdbc.query("select * from lesion_measurement where reading_session_id=? order by id",
                MEAS_MAPPER, readingSessionId);
    }

    public Optional<MeasurementRow> findMeasurementById(long id) {
        return jdbc.query("select * from lesion_measurement where id=?", MEAS_MAPPER, id).stream().findFirst();
    }

    /** 病灶在指定扫描之前最近一次可测轮廓（映射候选的轮廓约束依据） */
    public Optional<MeasurementRow> latestContourOfLesionBefore(long lesionId, LocalDate beforeDate) {
        return jdbc.query("""
                select m.* from lesion_measurement m
                join imaging_scan s on s.id = m.scan_id
                where m.lesion_id=? and m.measurable and m.contour_ref is not null and s.scan_date < ?
                order by s.scan_date desc, m.id desc limit 1""",
                MEAS_MAPPER, lesionId, beforeDate).stream().findFirst();
    }

    /** 受试者某扫描的现行（最新 FINAL 版本）测量 */
    public List<MeasurementRow> currentMeasurementsOfScan(long subjectId, long scanId) {
        return jdbc.query("""
                select m.* from lesion_measurement m
                join reading_session r on r.id = m.reading_session_id
                where m.subject_id=? and m.scan_id=? and r.status='FINAL'
                  and r.reading_version = (select max(reading_version) from reading_session where scan_id=?)
                order by m.id""", MEAS_MAPPER, subjectId, scanId, scanId);
    }

    public List<Map<String, Object>> measurementViewOfReading(long readingSessionId) {
        return jdbc.queryForList("""
                select m.id, m.lesion_id as "lesionId", l.lesion_key as "lesionKey",
                       m.is_new as "isNew", m.measurable, m.non_measurable_reason as "nonMeasurableReason",
                       m.diameter, m.axis, m.series_id as "seriesId",
                       m.contour_ref as "contourRef", m.evidence_ref as "evidenceRef",
                       m.created_at as "createdAt"
                from lesion_measurement m
                join target_lesion l on l.id = m.lesion_id
                where m.reading_session_id=? order by m.id""", readingSessionId);
    }
}
