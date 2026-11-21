-- 靶病灶纵向数据集 schema（在多源事实对账库内新增独立域，不改动 V1 表）
-- 分层：扫描/序列(协议元数据) -> 基线病灶登记 -> 随访阅片(测量/新病灶/不可测)
--      -> 映射候选/确认(父子关系) -> 映射运行序号 -> 指标版本(追加式历史) -> 疗效数据集(物化可复算)
-- 铁律：未确认候选不参与正式总和；缺值不记零；协议越界标不可比较而非进展。

-- ========== 1. 影像检查（携带协议元数据，供可比性判定） ==========
create table imaging_scan (
  id                     bigserial primary key,
  subject_id             bigint not null references subject(id),
  center_id              text not null,
  scan_key               text not null,              -- 来源系统检查号
  visit_code             text not null,
  scan_date              date not null,
  modality               text not null default 'CT',
  scanner_model          text,
  slice_thickness        numeric,                    -- 层厚 mm
  contrast_series        text,                       -- 增强序列描述
  protocol_status        text not null default 'OK',-- OK / PROTOCOL_DEVIATION
  protocol_reason        text,                       -- 越界说明（设备/层厚超出方案允许范围）
  comparable_to_baseline boolean not null default true, -- false=本次变化标不可比较，而非进展
  received_at            timestamptz not null default now(),
  created_by             text not null default 'system',
  unique (subject_id, scan_key)
);
create index idx_scan_subject on imaging_scan (subject_id, scan_date);

create table imaging_series (
  id              bigserial primary key,
  scan_id         bigint not null references imaging_scan(id) on delete cascade,
  series_key      text not null,
  modality        text,
  sequence_label  text not null,                     -- 影像序列，如 胸部增强CT轴位
  slice_thickness numeric,
  unique (scan_id, series_key)
);

-- ========== 2. 基线病灶登记：部位、测量轴、影像序列、研究受试者 ==========
create table target_lesion (
  id                 bigserial primary key,
  subject_id         bigint not null references subject(id),
  lesion_key         text not null unique,           -- 统一病灶编号，如 SUBJ-001/TL-01
  body_site          text not null,                  -- 解剖部位
  measurement_axis   text not null,                  -- LONG_AXIS / SHORT_AXIS
  baseline_scan_id   bigint not null references imaging_scan(id),
  baseline_series_id bigint references imaging_series(id),
  origin             text not null default 'BASELINE', -- BASELINE / NEW（随访新发现后登记）
  status             text not null default 'ACTIVE',   -- ACTIVE / NON_MEASURABLE
  note               text,
  registered_by      text not null default 'system',
  registered_at      timestamptz not null default now()
);
create index idx_lesion_subject on target_lesion (subject_id);

-- ========== 3. 随访阅片：每次阅片为一个版本，更正产生新版本而非覆盖 ==========
create table reading_session (
  id              bigserial primary key,
  subject_id      bigint not null references subject(id),
  scan_id         bigint not null references imaging_scan(id),
  reading_version int not null,
  reader          text not null,                     -- 阅片员
  read_at         date not null,
  status          text not null default 'FINAL',     -- DRAFT / FINAL / SUPERSEDED
  trigger_note    text,                              -- 初阅 / 更正说明
  created_by      text not null default 'system',
  created_at      timestamptz not null default now(),
  unique (scan_id, reading_version)
);
create index idx_reading_subject on reading_session (subject_id);

-- 每次测量：可测给直径；不可测给原因；新发现打 is_new 并登记为新病灶
create table lesion_measurement (
  id                    bigserial primary key,
  reading_session_id    bigint not null references reading_session(id) on delete cascade,
  scan_id               bigint not null references imaging_scan(id),
  subject_id            bigint not null references subject(id),
  lesion_id             bigint not null references target_lesion(id),
  is_new                boolean not null default false,
  measurable            boolean not null default true,
  non_measurable_reason text,                         -- 图像质量/伪影/无法勾画等，缺值不记零
  diameter              numeric,                      -- 靶病灶直径 mm（不可测时为空，禁止写 0）
  axis                  text,
  series_id             bigint references imaging_series(id),
  contour_ref           text,                         -- 测量轮廓/标注引用
  evidence_ref          text,                         -- 影像证据引用
  created_at            timestamptz not null default now(),
  unique (reading_session_id, lesion_id)
);
create index idx_meas_scan on lesion_measurement (scan_id);
create index idx_meas_lesion on lesion_measurement (lesion_id);

-- ========== 4. 映射候选与确认：自动生成受约束候选，未确认不得入正式总和 ==========
create table lesion_link_candidate (
  id               bigserial primary key,
  subject_id       bigint not null references subject(id),
  from_lesion_id   bigint references target_lesion(id),  -- 空=新发现候选
  to_measurement_id bigint not null references lesion_measurement(id) on delete cascade,
  relation_type    text not null,                    -- CONTINUATION / SPLIT / FUSION / NEW
  confidence       text not null,                    -- HIGH / MEDIUM / LOW
  score            numeric,                          -- 约束打分（解剖/序列/轮廓相似度）
  rationale        text not null,                    -- 命中规则与扣分理由
  auto_generated   boolean not null default true,
  status           text not null default 'CANDIDATE',-- CANDIDATE / CONFIRMED / REJECTED
  decided_by       text,
  decided_at       timestamptz,
  unique (from_lesion_id, to_measurement_id, relation_type)
);
create index idx_llc_measurement on lesion_link_candidate (to_measurement_id);
create index idx_llc_status on lesion_link_candidate (status);

-- 已确认映射（时间线边）。分裂：一个 from 对多个 to；融合：多个 from 对一个 to。
create table lesion_link (
  id               bigserial primary key,
  subject_id       bigint not null references subject(id),
  reading_session_id bigint not null references reading_session(id),
  from_lesion_id   bigint references target_lesion(id),  -- 空=新病灶（无父）
  to_lesion_id     bigint not null references target_lesion(id),
  to_measurement_id bigint not null references lesion_measurement(id) on delete cascade,
  relation_type    text not null,
  candidate_id     bigint references lesion_link_candidate(id),
  confidence       text not null,
  rationale        text not null,
  decided_by       text not null,
  created_at       timestamptz not null default now()
);
create index idx_link_subject on lesion_link (subject_id);
create index idx_link_from on lesion_link (from_lesion_id);
create index idx_link_to_meas on lesion_link (to_measurement_id);
-- 同一测量同一父病灶只允许一条确认边（新病灶 from 为空时每测量限一条）
create unique index uq_link_pair on lesion_link
  (to_measurement_id, coalesce(from_lesion_id, -1));

-- 映射运行：全局序号，指标版本与疗效数据集据此复算；按受试者触发
create table lesion_mapping_run (
  seq          bigserial primary key,
  subject_id   bigint not null references subject(id),
  trigger_note text,
  changed_links int not null default 0,
  created_at   timestamptz not null default now()
);

-- ========== 5. 受试者×扫描 指标版本（当前态 + 追加式历史） ==========
create table lesion_metric (
  id                  bigserial primary key,
  subject_id          bigint not null references subject(id),
  scan_id             bigint not null references imaging_scan(id),
  reading_session_id  bigint not null references reading_session(id),
  visit_code          text not null,
  scan_date           date not null,
  version             int not null default 1,
  seq                 bigint not null,               -- 最近一次重算所属映射序号
  sum_diameters       numeric,                       -- 正式总和（仅已确认映射的可测病灶）；不可评估为空
  baseline_sum        numeric,
  nadir_sum           numeric,                       -- 截至本次（含）历史最低正式总和
  pct_change_baseline numeric,                       -- 相对基线 %（不可比较时为空）
  pct_change_nadir    numeric,                       -- 相对最低值 %
  evaluable_status    text not null,                 -- EVALUABLE / NOT_EVALUABLE
  comparable          boolean not null default true, -- 协议越界或关键病灶缺测 -> false
  flags               jsonb not null default '[]',   -- SPLIT/FUSION/NEW_LESION/NON_MEASURABLE/PROTOCOL_DEVIATION/UNCONFIRMED_EXCLUDED
  contributing_lesion_ids jsonb not null default '[]', -- 实际计入总和的病灶 id（分裂后子病灶，父不重复）
  basis               jsonb not null,                -- {mappingRunSeq, readingVersion, ruleVersion, linkIds[], measurementIds[]}
  updated_at          timestamptz not null default now(),
  unique (subject_id, scan_id)
);
create index idx_metric_subject on lesion_metric (subject_id, scan_date);

create table lesion_metric_history (
  id                bigserial primary key,
  metric_id         bigint not null references lesion_metric(id),
  version           int not null,
  seq               bigint not null,
  sum_diameters     numeric,
  baseline_sum      numeric,
  nadir_sum         numeric,
  pct_change_baseline numeric,
  pct_change_nadir  numeric,
  evaluable_status  text not null,
  comparable        boolean not null,
  flags             jsonb not null,
  contributing_lesion_ids jsonb not null,
  basis             jsonb not null,
  created_at        timestamptz not null default now(),
  unique (metric_id, version)
);
create index idx_metric_history_seq on lesion_metric_history (seq);

-- ========== 6. 疗效数据集：记录所用扫描、阅片版本与映射依据，供统计派生终点 ==========
create table endpoint_dataset (
  id          bigserial primary key,
  name        text not null unique,
  created_at  timestamptz not null default now(),
  created_by  text not null,
  seq         bigint not null,                       -- 创建时映射序号（复算基准）
  rule_version text not null,
  gate        jsonb not null,                        -- {"includeNotEvaluable":false,"requireComparable":true}
  row_count   int not null default 0,
  scope_subject_ids jsonb                            -- 本次创建覆盖的受试者（更正后只重算受影响者的新版本）
);

create table endpoint_dataset_row (
  id                  bigserial primary key,
  dataset_id          bigint not null references endpoint_dataset(id) on delete cascade,
  subject_id          bigint not null references subject(id),
  scan_id             bigint not null references imaging_scan(id),
  visit_code          text not null,
  scan_date           date not null,
  metric_id           bigint not null references lesion_metric(id),
  metric_version      int not null,                  -- 物化时锚定的指标版本（旧终点可回放）
  sum_diameters       numeric,
  baseline_sum        numeric,
  nadir_sum           numeric,
  pct_change_baseline numeric,
  pct_change_nadir    numeric,
  evaluable_status    text not null,
  comparable          boolean not null,
  flags               jsonb not null,
  evidence            jsonb not null,                -- 扫描/阅片版本/映射依据/测量与确认边引用
  unique (dataset_id, subject_id, scan_id)
);
create index idx_endpoint_row_subject on endpoint_dataset_row (subject_id, scan_date);
