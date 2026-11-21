package com.example.clinicalrecon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 靶病灶纵向数据集验收：持续/新病灶、分裂、融合、不可测、协议越界、未确认门控、
 * 测量集合与变化比例、证据链、阅片更正后仅受影响受试者重算、旧终点可回放。
 * 依赖本地 PostgreSQL recon_test 库（Flyway 建表 V1+V2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LesionAcceptanceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private JsonNode callGet(String url) throws Exception {
        MvcResult r = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode callPost(String url, String body, String user, String role) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        if (user != null) req.header("X-User", user).header("X-Role", role);
        MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private static List<JsonNode> list(JsonNode arr) {
        var out = new ArrayList<JsonNode>();
        arr.forEach(out::add);
        return out;
    }

    private JsonNode metric(String subject, String scanKey) throws Exception {
        return list(callGet("/api/lesion-metrics?subjectKey=" + subject)).stream()
                .filter(m -> m.get("scanKey").asText().equals(scanKey)).findFirst().orElseThrow();
    }

    /** @Test 1：六类场景 + 门控 + 疗效数据集 + 证据链 */
    @Test
    @Order(1)
    void longitudinalScenariosAndGating() throws Exception {
        callPost("/api/demo/lesion-seed", null, "rad1", "RAD");

        // ---------- 登记层：各受试者扫描数（3+2+2+3=10 次检查） ----------
        assertEquals(3, list(callGet("/api/imaging/scans?subjectKey=SUBJ-001")).size());
        assertEquals(2, list(callGet("/api/imaging/scans?subjectKey=SUBJ-002")).size());
        assertEquals(2, list(callGet("/api/imaging/scans?subjectKey=SUBJ-003")).size());
        assertEquals(3, list(callGet("/api/imaging/scans?subjectKey=SUBJ-004")).size());

        // ---------- SUBJ-001：持续缩小 + 新病灶不并入 SLD ----------
        var s1bl = metric("SUBJ-001", "S1-BL");
        assertEquals(50.0, s1bl.get("sumDiameters").asDouble());
        var s1fu1 = metric("SUBJ-001", "S1-FU1");
        assertEquals(45.0, s1fu1.get("sumDiameters").asDouble());
        assertEquals(-10.0, s1fu1.get("pctChangeBaseline").asDouble());
        assertTrue(list(s1fu1.get("flags")).stream().anyMatch(f -> f.asText().equals("NEW_LESION")));
        // 新病灶 S1-TL03 不计入正式总和（contributing 仅两个基线病灶）
        var contrib1 = list(s1fu1.get("contributingLesionIds")).stream().map(JsonNode::asLong).toList();
        assertEquals(2, contrib1.size());
        var s1fu2 = metric("SUBJ-001", "S1-FU2");
        assertEquals(39.0, s1fu2.get("sumDiameters").asDouble());
        assertEquals(-22.0, s1fu2.get("pctChangeBaseline").asDouble());
        assertEquals(-13.3, s1fu2.get("pctChangeNadir").asDouble()); // 相对最低值 45

        // ---------- SUBJ-002：分裂，父子保留且父基线直径不重复比较 ----------
        var s2fu1 = metric("SUBJ-002", "S2-FU1");
        assertEquals(45.0, s2fu1.get("sumDiameters").asDouble());       // 25+20，不是 40+25+20
        assertEquals(12.5, s2fu1.get("pctChangeBaseline").asDouble());
        assertTrue(list(s2fu1.get("flags")).stream().anyMatch(f -> f.asText().equals("SPLIT")));
        assertEquals(2, list(s2fu1.get("contributingLesionIds")).size()); // 两个子代，父不重复
        var splitLinks = list(callGet("/api/lesion-links?subjectId=2")).stream()
                .filter(l -> l.get("relationType").asText().equals("SPLIT")).toList();
        assertEquals(2, splitLinks.size(), "分裂保留一个父到两个子的两条边");
        assertTrue(splitLinks.stream().allMatch(l -> l.get("fromLesionKey").asText().equals("S2-TL11")));
        assertTrue(splitLinks.stream().allMatch(l -> l.get("decidedBy").asText().equals("system:auto-mapper")));

        // ---------- SUBJ-003：融合，两个父汇入一个测量，只计一次 ----------
        var s3fu1 = metric("SUBJ-003", "S3-FU1");
        assertEquals(30.0, s3fu1.get("sumDiameters").asDouble());       // 单一融合病灶 30
        assertEquals(-25.0, s3fu1.get("pctChangeBaseline").asDouble());
        assertTrue(list(s3fu1.get("flags")).stream().anyMatch(f -> f.asText().equals("FUSION")));
        var fusionLinks = list(callGet("/api/lesion-links?subjectId=3")).stream()
                .filter(l -> l.get("relationType").asText().equals("FUSION")).toList();
        assertEquals(2, fusionLinks.size(), "融合保留两个父到一个子的两条边");

        // ---------- SUBJ-004：协议越界不可比较 + 不可测缺值非零 + 未确认门控 ----------
        var s4fu1 = metric("SUBJ-004", "S4-FU1");
        assertEquals("NOT_EVALUABLE", s4fu1.get("evaluableStatus").asText());
        assertFalse(s4fu1.get("comparable").asBoolean());
        assertTrue(s4fu1.get("pctChangeBaseline") == null || s4fu1.get("pctChangeBaseline").isNull(),
                "协议越界变化必须留空，不判进展");
        var flags4 = list(s4fu1.get("flags")).stream().map(JsonNode::asText).toList();
        assertTrue(flags4.contains("PROTOCOL_DEVIATION") && flags4.contains("NON_MEASURABLE"));
        assertEquals(60.0, s4fu1.get("sumDiameters").asDouble(), "可测部分仍保留原始测量和");

        // 不可测病灶：直径为空（缺值不记零），原因保留
        var nmReading = list(callGet("/api/readings?subjectKey=SUBJ-004")).stream()
                .filter(r -> list(r.get("measurements")).stream()
                        .anyMatch(m -> m.get("lesionKey").asText().equals("S4-TL32")
                                && !m.get("measurable").asBoolean()))
                .findFirst().orElseThrow();
        var nmMeas = list(nmReading.get("measurements")).stream()
                .filter(m -> m.get("lesionKey").asText().equals("S4-TL32")).findFirst().orElseThrow();
        assertTrue(nmMeas.get("diameter") == null || nmMeas.get("diameter").isNull(), "缺值不得记零");
        assertFalse(nmMeas.get("nonMeasurableReason").asText().isBlank());

        // 未确认候选门控：FU2 的 TL32 为 MEDIUM 候选，未确认前不入总和
        var pending = list(callGet("/api/lesion-links/candidates?status=CANDIDATE")).stream()
                .filter(c -> c.get("toLesionKey").asText().equals("S4-TL32")).toList();
        assertFalse(pending.isEmpty(), "低置信关系应保留待复核候选");
        var s4fu2Before = metric("SUBJ-004", "S4-FU2");
        assertEquals(35.0, s4fu2Before.get("sumDiameters").asDouble(), "未确认测量被排除，仅 TL31=35");
        assertEquals("NOT_EVALUABLE", s4fu2Before.get("evaluableStatus").asText());
        assertTrue(list(s4fu2Before.get("flags")).stream()
                .anyMatch(f -> f.asText().equals("UNCONFIRMED_EXCLUDED")));

        // ---------- 疗效数据集（默认门槛：仅 EVALUABLE 且可比较） ----------
        var ep1 = callPost("/api/endpoint-datasets",
                "{\"name\":\"EP-1\",\"includeNotEvaluable\":false,\"requireComparable\":true}", "dm1", "DM");
        long ep1Id = ep1.get("id").asLong();
        var ep1Rows = list(callGet("/api/endpoint-datasets/" + ep1Id + "/rows"));
        assertEquals(8, ep1Rows.size(), "SUBJ-004 两次 NOT_EVALUABLE 被排除");
        assertTrue(ep1Rows.stream().noneMatch(r -> r.get("evaluableStatus").asText().equals("NOT_EVALUABLE")));
        // 每行证据链含扫描/阅片版本/映射依据
        var sample = ep1Rows.stream().filter(r -> r.get("subjectKey").asText().equals("SUBJ-002")).findFirst().orElseThrow();
        assertNotNull(sample.get("evidence").get("scanKey"));
        assertNotNull(sample.get("evidence").get("readingVersion"));
        assertNotNull(sample.get("evidence").get("confirmedLinkIds"));
        var verify1 = callGet("/api/endpoint-datasets/" + ep1Id + "/verify");
        assertTrue(verify1.get("reproducible").asBoolean());

        // ---------- 人工确认候选后指标转 EVALUABLE，旧数据集仍可回放 ----------
        // 选择指向 FU2（最新一次复查）的待复核候选
        long candId = pending.stream()
                .map(c -> c.get("id").asLong()).max(Long::compare).orElseThrow();
        callPost("/api/lesion-links/candidates/" + candId + "/confirm", null, "rad1", "RAD");
        var s4fu2After = metric("SUBJ-004", "S4-FU2");
        assertEquals("EVALUABLE", s4fu2After.get("evaluableStatus").asText());
        assertEquals(53.0, s4fu2After.get("sumDiameters").asDouble()); // 35+18
        assertTrue(s4fu2After.get("version").asInt() >= 2, "真实变化才跃迁版本");
        var verify1b = callGet("/api/endpoint-datasets/" + ep1Id + "/verify");
        // 回放以 EP-1 创建时的映射序号为界，精确重建旧门槛结果：FU2 当时 NOT_EVALUABLE 本就不在其中，
        // 故旧物化行不变、可复现；确认带来的新增可评估指标不会回写旧数据集，而由新数据集 EP-2 体现
        assertTrue(verify1b.get("reproducible").asBoolean(), "旧终点物化行不变，按序号回放一致");
        assertEquals(0, list(verify1b.get("extraInReplay")).size());

        // 新数据集纳入新确认的测量（SUBJ-004 FU2 进入，行数 8→9）
        var ep2 = callPost("/api/endpoint-datasets",
                "{\"name\":\"EP-2\",\"includeNotEvaluable\":false,\"requireComparable\":true}", "dm1", "DM");
        var ep2Rows = list(callGet("/api/endpoint-datasets/" + ep2.get("id").asLong() + "/rows"));
        assertEquals(9, ep2Rows.size());
        assertTrue(ep2Rows.stream().anyMatch(r -> r.get("subjectKey").asText().equals("SUBJ-004")
                && r.get("scanKey").asText().equals("S4-FU2")
                && r.get("evaluableStatus").asText().equals("EVALUABLE")));

        // ---------- 证据链 trace 与时间线 ----------
        long traceId = s2fu1.get("id").asLong();
        var trace = callGet("/api/lesion-metrics/" + traceId + "/trace");
        assertFalse(list(trace.get("history")).isEmpty());
        assertEquals(2, list(trace.get("evidence").get("links")).size());
        assertEquals("lesion-recon-v1.0", trace.get("evidence").get("basis").get("ruleVersion").asText());
        var timeline = callGet("/api/lesion-metrics/timeline?subjectKey=SUBJ-002");
        var blNode = list(timeline.get("nodes")).stream()
                .filter(n -> n.get("scanKey").asText().equals("S2-BL")).findFirst().orElseThrow();
        assertEquals("BASELINE", blNode.get("relationType").asText());
        assertTrue(list(timeline.get("nodes")).stream()
                .filter(n -> n.get("relationType").asText().equals("SPLIT")).count() == 2);
    }

    /** @Test 2：阅片更正 → 仅受影响受试者重算，旧终点可回放，新版本反映更正 */
    @Test
    @Order(2)
    void correctionScopedRecomputeAndReplay() throws Exception {
        // 更正前自建数据集，锚定更正前的指标版本
        var epBefore = callPost("/api/endpoint-datasets",
                "{\"name\":\"EP-CORR-BEFORE\",\"includeNotEvaluable\":false,\"requireComparable\":true}",
                "dm1", "DM");
        long epBeforeId = epBefore.get("id").asLong();

        long fu1VersionBefore = metric("SUBJ-001", "S1-FU1").get("version").asLong();
        long otherVersionBefore = metric("SUBJ-002", "S2-FU1").get("version").asLong();

        String correction = new String(new ClassPathResource("samples/lesions/lesion-reading-correction.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var out = callPost("/api/readings/correct?subjectKey=SUBJ-001&scanKey=S1-FU1",
                correction, "rad1", "RAD");
        assertEquals(2, out.get("readingVersion").asInt());
        assertEquals("SUPERSEDED", list(callGet("/api/readings?subjectKey=SUBJ-001")).stream()
                .filter(r -> r.get("scanKey").asText().equals("S1-FU1") && r.get("readingVersion").asInt() == 1)
                .findFirst().orElseThrow().get("status").asText());

        // 受影响受试者指标版本跃迁，总和由 45 改为 44（26+18，新病灶仍不计入）
        var fu1After = metric("SUBJ-001", "S1-FU1");
        assertTrue(fu1After.get("version").asLong() > fu1VersionBefore);
        assertEquals(44.0, fu1After.get("sumDiameters").asDouble());
        // 下游 FU2 的相对最低值随之重算（最低值由 45 变 44）
        assertEquals(-11.4, metric("SUBJ-001", "S1-FU2").get("pctChangeNadir").asDouble());
        // 其他受试者指标版本不变
        assertEquals(otherVersionBefore, metric("SUBJ-002", "S2-FU1").get("version").asLong());

        // 旧数据集物化行不变、仍可回放，并标注 SUBJ-001 为受影响受试者
        var verify = callGet("/api/endpoint-datasets/" + epBeforeId + "/verify");
        assertTrue(verify.get("reproducible").asBoolean());
        assertTrue(list(verify.get("changedSubjects")).stream().anyMatch(s -> s.asText().equals("SUBJ-001")));
    }

    /** @Test 3：录入校验——缺值不得记零、可测必须有直径、新病灶须先登记、扫描幂等 */
    @Test
    @Order(3)
    void inputGuards() throws Exception {
        // 新建一次尚无阅片的检查，使测量级校验可被触达
        callPost("/api/imaging/scans", """
                {"subjectKey":"SUBJ-001","centerId":"C01","scanKey":"S1-V4","visitCode":"V4",
                 "scanDate":"2026-04-13","sliceThickness":2.0,
                 "series":[{"seriesKey":"S1-V4-S1","sequenceLabel":"胸腹部增强CT轴位","sliceThickness":2.0}]}""",
                "rad1", "RAD");

        // 不可测却给 0 直径 → 拒绝（缺值不能记零）
        String zeroDiameter = """
                {"subjectKey":"SUBJ-001","scanKey":"S1-V4","reader":"rad1","readAt":"2026-04-14",
                 "measurements":[{"lesionKey":"S1-TL01","measurable":false,"diameter":0,
                                  "nonMeasurableReason":"伪影"}]}""";
        mvc.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON).content(zeroDiameter))
                .andExpect(status().isBadRequest());

        // 可测但不给直径 → 拒绝
        String noDiameter = """
                {"subjectKey":"SUBJ-001","scanKey":"S1-V4","reader":"rad1","readAt":"2026-04-14",
                 "measurements":[{"lesionKey":"S1-TL01","measurable":true}]}""";
        mvc.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON).content(noDiameter))
                .andExpect(status().isBadRequest());

        // 未登记病灶 → 404
        String unknownLesion = """
                {"subjectKey":"SUBJ-001","scanKey":"S1-V4","reader":"rad1","readAt":"2026-04-14",
                 "measurements":[{"lesionKey":"GHOST","measurable":true,"diameter":10,"axis":"LONG_AXIS"}]}""";
        mvc.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON).content(unknownLesion))
                .andExpect(status().isNotFound());

        // 重复扫描登记 → 409
        String dupScan = """
                {"subjectKey":"SUBJ-001","centerId":"C01","scanKey":"S1-BL","visitCode":"V1",
                 "scanDate":"2026-03-02","sliceThickness":2.0}""";
        mvc.perform(post("/api/imaging/scans").contentType(MediaType.APPLICATION_JSON).content(dupScan))
                .andExpect(status().isConflict());
    }
}
