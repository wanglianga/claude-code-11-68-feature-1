package com.example.clinicalrecon.domain;

/** 领域常量：全部以字符串落库，此处集中定义取值域，避免散落字面量。 */
public final class Enums {
    private Enums() {}

    /** 来源系统 */
    public static final class Source {
        public static final String CRF = "CRF";
        public static final String LAB = "LAB";
        public static final String IMAGING = "IMAGING";
        public static final String MONITORING = "MONITORING";
        public static final java.util.Set<String> ALL = java.util.Set.of(CRF, LAB, IMAGING, MONITORING);
    }

    /** 时间精度 */
    public static final class Precision {
        public static final String DATETIME = "DATETIME";
        public static final String DATE = "DATE";
        public static final java.util.Set<String> ALL = java.util.Set.of(DATETIME, DATE);
    }

    /** 字段映射状态 */
    public static final class Mapping {
        public static final String MAPPED = "MAPPED";
        public static final String UNMAPPED = "UNMAPPED";
    }

    /** 原始事实关联状态 */
    public static final class Link {
        public static final String UNLINKED = "UNLINKED";
        public static final String PENDING_DECISION = "PENDING_DECISION";
        public static final String LINKED = "LINKED";
    }

    /** 候选置信度 */
    public static final class Confidence {
        public static final String HIGH = "HIGH";
        public static final String MEDIUM = "MEDIUM";
        public static final String LOW = "LOW";
    }

    /** 候选状态 */
    public static final class Candidate {
        public static final String CANDIDATE = "CANDIDATE";
        public static final String CONFIRMED = "CONFIRMED";
        public static final String REJECTED = "REJECTED";
    }

    /** 协调状态（对外发布的四态） */
    public static final class Recon {
        public static final String CONFIRMED = "CONFIRMED";
        public static final String CONFLICT = "CONFLICT";
        public static final String MISSING = "MISSING";
        public static final String PENDING = "PENDING";
    }

    /** 协调规则码 */
    public static final class Rule {
        public static final String SINGLE_SOURCE = "SINGLE_SOURCE";
        public static final String MULTI_AGREE = "MULTI_AGREE";
        public static final String MUTUALLY_EXCLUSIVE = "MUTUALLY_EXCLUSIVE";
        public static final String MANUAL_RESOLUTION = "MANUAL_RESOLUTION";
        public static final String NO_SOURCE = "NO_SOURCE";
        public static final String UNMAPPED_FIELD = "UNMAPPED_FIELD";
        public static final String LINK_UNDECIDED = "LINK_UNDECIDED";
        public static final String NO_CANDIDATE = "NO_CANDIDATE";
    }

    /** 协调-来源角色 */
    public static final class SourceRole {
        public static final String CONFIRMING = "CONFIRMING";
        public static final String CONFLICTING = "CONFLICTING";
        public static final String SUPERSEDED = "SUPERSEDED";
        public static final String PENDING_EVIDENCE = "PENDING_EVIDENCE";
    }

    /** 质疑状态 */
    public static final class Query {
        public static final String OPEN = "OPEN";
        public static final String ANSWERED = "ANSWERED";
        public static final String CLOSED = "CLOSED";
        public static final String REOPENED = "REOPENED";
    }

    /** 质疑类型 */
    public static final class Issue {
        public static final String CONFLICT = "CONFLICT";
        public static final String UNMAPPED_FIELD = "UNMAPPED_FIELD";
        public static final String LINK_AMBIGUITY = "LINK_AMBIGUITY";
        public static final String MONITORING_FINDING = "MONITORING_FINDING";
        public static final String MISSING_DATA = "MISSING_DATA";
    }

    /** 责任角色 */
    public static final class Role {
        public static final String DM = "DM";       // 数据管理
        public static final String CRA = "CRA";     // 临床监查
        public static final String LAB = "LAB";     // 中心实验室
        public static final String PI = "PI";       // 主要研究者
        public static final String RAD = "RAD";     // 独立阅片员（影像评估）
    }

    // ==================== 靶病灶纵向域 ====================

    /** 扫描协议状态 */
    public static final class Protocol {
        public static final String OK = "OK";
        public static final String DEVIATION = "PROTOCOL_DEVIATION";
    }

    /** 测量轴 */
    public static final class Axis {
        public static final String LONG = "LONG_AXIS";
        public static final String SHORT = "SHORT_AXIS";
        public static final java.util.Set<String> ALL = java.util.Set.of(LONG, SHORT);
    }

    /** 病灶来源/状态 */
    public static final class LesionOrigin {
        public static final String BASELINE = "BASELINE"; // 基线登记
        public static final String NEW = "NEW";           // 随访新发现（可能提示进展，不并入 SLD）
        public static final String DERIVED = "DERIVED";   // 分裂/融合子代：同一疾病谱系，非新发病灶
        public static final java.util.Set<String> ALL =
                java.util.Set.of(BASELINE, NEW, DERIVED);
    }

    public static final class LesionStatus {
        public static final String ACTIVE = "ACTIVE";
        public static final String NON_MEASURABLE = "NON_MEASURABLE";
    }

    /** 阅片版本状态 */
    public static final class Reading {
        public static final String DRAFT = "DRAFT";
        public static final String FINAL = "FINAL";
        public static final String SUPERSEDED = "SUPERSEDED";
    }

    /** 病灶间映射关系 */
    public static final class Relation {
        public static final String CONTINUATION = "CONTINUATION"; // 持续
        public static final String SPLIT = "SPLIT";               // 分裂
        public static final String FUSION = "FUSION";             // 融合
        public static final String NEW = "NEW";                   // 新病灶
        public static final java.util.Set<String> ALL =
                java.util.Set.of(CONTINUATION, SPLIT, FUSION, NEW);
    }

    /** 指标可判定状态 */
    public static final class Evaluable {
        public static final String EVALUABLE = "EVALUABLE";
        public static final String NOT_EVALUABLE = "NOT_EVALUABLE";
    }

    /** 指标/证据标记 */
    public static final class LesionFlag {
        public static final String SPLIT = "SPLIT";
        public static final String FUSION = "FUSION";
        public static final String NEW_LESION = "NEW_LESION";
        public static final String NON_MEASURABLE = "NON_MEASURABLE";
        public static final String PROTOCOL_DEVIATION = "PROTOCOL_DEVIATION";
        public static final String UNCONFIRMED_EXCLUDED = "UNCONFIRMED_EXCLUDED";
    }

    /** 映射规则版本（随计算器逻辑升级而变更，写入指标 basis 与疗效数据集） */
    public static final String LESION_RULE_VERSION = "lesion-recon-v1.0";
}
