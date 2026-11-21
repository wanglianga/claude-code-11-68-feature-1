package com.example.clinicalrecon.service;

import com.example.clinicalrecon.config.ApiExceptionHandler.BadRequestException;
import com.example.clinicalrecon.config.ApiExceptionHandler.NotFoundException;
import com.example.clinicalrecon.config.UserContext;
import com.example.clinicalrecon.domain.Enums;
import com.example.clinicalrecon.domain.LesionDtos;
import com.example.clinicalrecon.repo.ImagingRepo;
import com.example.clinicalrecon.repo.LesionLinkRepo;
import com.example.clinicalrecon.repo.MasterDataRepo;
import com.example.clinicalrecon.repo.ReadingRepo;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 病灶映射计算器（候选层）。
 * 两种路线服务同一纵向测量：
 *  - 自动：以解剖位置、影像序列、测量轮廓为约束打分生成候选，唯一高置信自动确认，其余留阅片员复核；
 *  - 人工：阅片员逐次确认/驳回候选或直接落确认边。
 * 铁律：相似位置不等于身份事实——未确认候选一律不参与正式总和（由指标计算器执行门控）。
 * 分裂：同一父病灶确认到多个本次测量（保留父子、父直径不重复比较）；
 * 融合：多个父病灶确认到同一本次测量。
 */
@Service
public class LesionLinkingService {
    private final LesionLinkRepo linkRepo;
    private final ImagingRepo imagingRepo;
    private final ReadingRepo readingRepo;
    private final MasterDataRepo masterRepo;
    private final LesionMetricService metricService;

    static final Pattern CONTOUR = Pattern.compile("x:\\s*([\\d.]+)[,\\s]+y:\\s*([\\d.]+)[,\\s]+z:\\s*([\\d.]+)");

    public LesionLinkingService(LesionLinkRepo linkRepo, ImagingRepo imagingRepo, ReadingRepo readingRepo,
                                MasterDataRepo masterRepo, @Lazy LesionMetricService metricService) {
        this.linkRepo = linkRepo;
        this.imagingRepo = imagingRepo;
        this.readingRepo = readingRepo;
        this.masterRepo = masterRepo;
        this.metricService = metricService;
    }

    private record Scored(long lesionId, int score, String confidence, String rationale) {}

    /** 为一次阅片的全部测量生成受约束候选，并自动确认唯一高置信关系。 */
    @Transactional
    public LesionDtos.AutoMapOut generateForReading(long readingId) {
        var reading = readingRepo.findReadingById(readingId)
                .orElseThrow(() -> new NotFoundException("阅片不存在: " + readingId));
        var scan = imagingRepo.findScanById(reading.scanId())
                .orElseThrow(() -> new NotFoundException("检查不存在: " + reading.scanId()));
        var measurements = readingRepo.measurementsOfReading(readingId);
        var messages = new ArrayList<String>();
        int generated = 0, autoConfirmed = 0, pending = 0;

        // 本次扫描之前已存在的病灶（候选父身份）
        var priorLesions = imagingRepo.lesionsOfSubject(reading.subjectId()).stream()
                .filter(l -> imagingRepo.findScanById(l.baselineScanId())
                        .map(s -> s.scanDate().isBefore(scan.scanDate())).orElse(false))
                .toList();
        // 基线（受试者最早）扫描的测量定义病灶身份，本身不需要映射候选
        boolean baselineScan = imagingRepo.scansOfSubject(reading.subjectId()).stream()
                .findFirst().map(s -> s.id() == scan.id()).orElse(false);

        Map<Long, Long> bestParent = new LinkedHashMap<>();
        Map<Long, List<Scored>> allScored = new LinkedHashMap<>();

        for (var m : measurements) {
            if (m.isNew()) {
                // 阅片声明的新发现：身份主张明确，无父，给 HIGH 候选并自动确认（关系 NEW）
                long cid = linkRepo.insertCandidate(reading.subjectId(), null, m.id(), Enums.Relation.NEW,
                        Enums.Confidence.HIGH, new BigDecimal("100"),
                        "阅片声明为随访新发现病灶，无既往身份可映射", true);
                if (cid > 0) {
                    generated++;
                    linkRepo.decideCandidate(cid, Enums.Candidate.CONFIRMED, "system:auto-mapper");
                    linkRepo.insertLink(reading.subjectId(), readingId, null, m.lesionId(), m.id(),
                            Enums.Relation.NEW, cid, Enums.Confidence.HIGH,
                            "新发现病灶，阅片员声明", "system:auto-mapper");
                    autoConfirmed++;
                }
                continue;
            }
            if (baselineScan) continue; // 基线测量定义身份，不生成候选
            var toLesion = imagingRepo.findLesionById(m.lesionId()).orElseThrow();
            var scored = new ArrayList<Scored>();
            for (var prior : priorLesions) {
                // 约束一：解剖位置不同直接排除——相似位置不等于身份事实
                if (!prior.bodySite().equalsIgnoreCase(toLesion.bodySite())) continue;
                int score = 40;
                var reasons = new ArrayList<String>();
                reasons.add("同解剖部位(" + prior.bodySite() + ")+40");
                if (prior.measurementAxis().equalsIgnoreCase(toLesion.measurementAxis())) {
                    score += 20;
                    reasons.add("同测量轴+20");
                }
                if (m.seriesId() != null && m.seriesId().equals(prior.baselineSeriesId())) {
                    score += 20;
                    reasons.add("同影像序列+20");
                }
                var priorContour = readingRepo.latestContourOfLesionBefore(prior.id(), scan.scanDate())
                        .map(ReadingRepo.MeasurementRow::contourRef).orElse(null);
                double dist = contourDistance(priorContour, m.contourRef());
                if (dist >= 0) {
                    if (dist <= 30) { score += 20; reasons.add("轮廓距离" + Math.round(dist) + "mm≤30 +20"); }
                    else if (dist <= 60) { score += 10; reasons.add("轮廓距离" + Math.round(dist) + "mm≤60 +10"); }
                    else reasons.add("轮廓距离" + Math.round(dist) + "mm>60 不加分");
                }
                String confidence = score >= 80 ? Enums.Confidence.HIGH
                        : score >= 50 ? Enums.Confidence.MEDIUM : Enums.Confidence.LOW;
                scored.add(new Scored(prior.id(), score, confidence, String.join("；", reasons)
                        + "；约束打分=" + score + "（" + confidence + "）"));
            }
            scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
            allScored.put(m.id(), scored);
            if (!scored.isEmpty()) bestParent.put(m.id(), scored.get(0).lesionId());
        }

        // 分裂检测：同一父病灶成为多个本次测量的最佳归属
        var parentChildCount = new HashMap<Long, Integer>();
        bestParent.values().forEach(p -> parentChildCount.merge(p, 1, Integer::sum));

        for (var m : measurements) {
            if (m.isNew() || baselineScan) continue;
            var scored = allScored.getOrDefault(m.id(), List.of());
            if (scored.isEmpty()) {
                messages.add("测量#" + m.id() + " 无满足解剖约束的既往病灶，保持未映射待人工处理");
                pending++;
                continue;
            }
            Scored top = scored.get(0);
            boolean tie = scored.size() > 1 && scored.get(1).score() == top.score();
            for (var s : scored) {
                String relation = parentChildCount.getOrDefault(s.lesionId(), 0) > 1
                        && s.lesionId() == top.lesionId()
                        ? Enums.Relation.SPLIT : Enums.Relation.CONTINUATION;
                String rationale = s.rationale();
                if (Enums.Relation.SPLIT.equals(relation)) {
                    rationale = "父病灶在本次复查分裂为多个可测部分，保留父子关系且父直径不重复比较；" + rationale;
                }
                long cid = linkRepo.insertCandidate(reading.subjectId(), s.lesionId(), m.id(), relation,
                        s.confidence(), new BigDecimal(s.score()), rationale, true);
                if (cid > 0) generated++;
            }
            // 唯一且 HIGH 的最佳候选自动确认；平分或中低置信留阅片员复核
            if (!tie && Enums.Confidence.HIGH.equals(top.confidence())
                    && !linkRepo.measurementHasLink(m.id())) {
                String relation = parentChildCount.getOrDefault(top.lesionId(), 0) > 1
                        ? Enums.Relation.SPLIT : Enums.Relation.CONTINUATION;
                long cid = linkRepo.candidatesOfMeasurement(m.id()).stream()
                        .filter(c -> c.fromLesionId() != null && c.fromLesionId() == top.lesionId()
                                && c.relationType().equals(relation))
                        .map(LesionLinkRepo.CandidateRow::id).findFirst().orElse(-1L);
                if (cid > 0) {
                    linkRepo.decideCandidate(cid, Enums.Candidate.CONFIRMED, "system:auto-mapper");
                    linkRepo.insertLink(reading.subjectId(), readingId, top.lesionId(), m.lesionId(), m.id(),
                            relation, cid, top.confidence(), "约束打分唯一高置信自动确认", "system:auto-mapper");
                    if (Enums.Relation.SPLIT.equals(relation)) {
                        markSiblingSplit(reading.subjectId(), readingId, top.lesionId());
                    }
                    autoConfirmed++;
                }
            } else {
                pending++;
                messages.add("测量#" + m.id() + (tie ? " 存在平分候选" : " 最高置信仅 " + top.confidence())
                        + "，留阅片员复核，未确认前不参与正式总和");
            }
        }
        return new LesionDtos.AutoMapOut(reading.subjectId(), generated, autoConfirmed, pending, messages);
    }

    /** 人工确认候选；自动识别融合（同一测量多个父）与分裂（同一父多个测量）。 */
    @Transactional
    public LesionDtos.LinkOut confirmCandidate(long candidateId) {
        var cand = linkRepo.findCandidate(candidateId)
                .orElseThrow(() -> new NotFoundException("候选不存在: " + candidateId));
        if (!Enums.Candidate.CANDIDATE.equals(cand.status())) {
            throw new BadRequestException("候选已裁定，状态=" + cand.status());
        }
        var m = readingRepo.findMeasurementById(cand.toMeasurementId())
                .orElseThrow(() -> new NotFoundException("测量不存在: " + cand.toMeasurementId()));
        String user = UserContext.user();
        String relation = cand.relationType();

        var existing = linkRepo.linksToMeasurements(List.of(m.id()));
        // 融合：该测量已确认来自另一父病灶 → 既有边与新边统一标 FUSION
        if (!existing.isEmpty() && existing.stream().anyMatch(l -> !l.fromLesionId().equals(cand.fromLesionId()))) {
            relation = Enums.Relation.FUSION;
            existing.forEach(l -> linkRepo.updateRelation(l.id(), Enums.Relation.FUSION));
        }
        // 分裂：同父病灶在同次阅片已确认到另一测量
        if (cand.fromLesionId() != null) {
            boolean sibling = linkRepo.linksOfSubject(cand.subjectId()).stream()
                    .anyMatch(l -> l.readingSessionId() == m.readingSessionId()
                            && cand.fromLesionId().equals(l.fromLesionId())
                            && l.toMeasurementId() != m.id());
            if (sibling) relation = Enums.Relation.SPLIT;
        }

        linkRepo.decideCandidate(candidateId, Enums.Candidate.CONFIRMED, user);
        long linkId = linkRepo.insertLink(cand.subjectId(), m.readingSessionId(), cand.fromLesionId(),
                m.lesionId(), m.id(), relation, candidateId, cand.confidence(),
                "人工确认候选#" + candidateId + "（" + cand.rationale() + "）", user);
        if (Enums.Relation.SPLIT.equals(relation)) {
            markSiblingSplit(cand.subjectId(), m.readingSessionId(), cand.fromLesionId());
        }
        metricService.recomputeSubject(cand.subjectId(), "link:confirm candidate " + candidateId);
        return linkOut(linkRepo.allLinks().stream().filter(l -> l.id() == linkId).findFirst()
                .orElseThrow());
    }

    /** 人工驳回候选（未确认候选本就不入总和，驳回仅清理待复核清单并重算标记） */
    @Transactional
    public void rejectCandidate(long candidateId) {
        var cand = linkRepo.findCandidate(candidateId)
                .orElseThrow(() -> new NotFoundException("候选不存在: " + candidateId));
        if (!Enums.Candidate.CANDIDATE.equals(cand.status())) {
            throw new BadRequestException("候选已裁定，状态=" + cand.status());
        }
        linkRepo.decideCandidate(candidateId, Enums.Candidate.REJECTED, UserContext.user());
        metricService.recomputeSubject(cand.subjectId(), "link:reject candidate " + candidateId);
    }

    /** 直接人工落确认边（阅片员逐次确认路线，无候选时使用） */
    @Transactional
    public LesionDtos.LinkOut manualLink(LesionDtos.ManualLinkIn in) {
        var m = readingRepo.findMeasurementById(in.toMeasurementId())
                .orElseThrow(() -> new NotFoundException("测量不存在: " + in.toMeasurementId()));
        if (!Enums.Relation.ALL.contains(in.relationType())) {
            throw new BadRequestException("非法关系类型: " + in.relationType());
        }
        boolean isNew = Enums.Relation.NEW.equals(in.relationType());
        if (isNew != (in.fromLesionId() == null)) {
            throw new BadRequestException("NEW 关系必须无父病灶；其余关系必须指定父病灶");
        }
        if (!isNew && imagingRepo.findLesionById(in.fromLesionId()).isEmpty()) {
            throw new NotFoundException("父病灶不存在: " + in.fromLesionId());
        }
        if (linkRepo.measurementHasLink(m.id()) && !isNew) {
            var existing = linkRepo.linksToMeasurements(List.of(m.id()));
            if (existing.stream().anyMatch(l -> !l.fromLesionId().equals(in.fromLesionId()))) {
                // 多父汇入同一测量 → 融合
                existing.forEach(l -> linkRepo.updateRelation(l.id(), Enums.Relation.FUSION));
            }
        }
        String confidence = in.confidence() == null ? Enums.Confidence.HIGH : in.confidence();
        long id = linkRepo.insertLink(m.subjectId(), m.readingSessionId(), in.fromLesionId(), m.lesionId(),
                m.id(), in.relationType(), null, confidence,
                in.rationale() == null ? "阅片员逐次人工确认" : in.rationale(), UserContext.user());
        if (id < 0) throw new BadRequestException("该测量与父病灶的确认边已存在");
        metricService.recomputeSubject(m.subjectId(), "link:manual by " + UserContext.user());
        return linkOut(linkRepo.allLinks().stream().filter(l -> l.id() == id).findFirst().orElseThrow());
    }

    private void markSiblingSplit(long subjectId, long readingId, long parentLesionId) {
        linkRepo.linksOfSubject(subjectId).stream()
                .filter(l -> l.readingSessionId() == readingId
                        && l.fromLesionId() != null && l.fromLesionId() == parentLesionId)
                .forEach(l -> linkRepo.updateRelation(l.id(), Enums.Relation.SPLIT));
    }

    /** 轮廓坐标解析；无法解析返回 -1（缺元数据不构成身份证据，不加分也不扣分） */
    static double contourDistance(String a, String b) {
        if (a == null || b == null) return -1;
        Matcher ma = CONTOUR.matcher(a), mb = CONTOUR.matcher(b);
        if (!ma.find() || !mb.find()) return -1;
        double sum = 0;
        for (int i = 1; i <= 3; i++) {
            double d = Double.parseDouble(ma.group(i)) - Double.parseDouble(mb.group(i));
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    public LesionDtos.LinkOut linkOut(LesionLinkRepo.LinkRow l) {
        String subjectKey = masterRepo.findSubjectById(l.subjectId())
                .map(MasterDataRepo.SubjectRow::subjectKey).orElse("?");
        return new LesionDtos.LinkOut(l.id(), l.subjectId(), subjectKey, l.readingSessionId(),
                l.fromLesionId(),
                l.fromLesionId() == null ? null
                        : imagingRepo.findLesionById(l.fromLesionId()).map(ImagingRepo.LesionRow::lesionKey).orElse("?"),
                l.toLesionId(),
                imagingRepo.findLesionById(l.toLesionId()).map(ImagingRepo.LesionRow::lesionKey).orElse("?"),
                l.toMeasurementId(), l.relationType(), l.candidateId(), l.confidence(),
                l.rationale(), l.decidedBy());
    }
}
