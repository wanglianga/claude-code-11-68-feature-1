# 临床试验多源事实对账与靶病灶纵向数据服务

本服务在同一工程内交付两条可追溯数据链路：

1. **多源事实对账**：eCRF、中心实验室、影像评估、监查四类来源各自上送事实，
   完成 **受理仓 → 受试者/访视关联 → 事实协调 → 质疑闭环 → 分析快照** 的全链路对账，
   产出可复算、可回溯的数据集，避免未经核对的字段进入中期分析。
2. **靶病灶纵向数据集**：基线登记病灶（部位、测量轴、影像序列、研究受试者），
   随访阅片保存每次测量、病灶对应候选、新病灶与不可测原因；映射计算器形成病灶时间线，
   按方案规则计算直径总和（SLD）、相对基线/最低值变化与可判定状态；疗效数据集记录所用扫描、
   阅片版本与映射依据，供统计团队派生终点（**不代替医学裁定**）。

技术栈：Java 25 · Spring Boot 3.5 · PostgreSQL 18 · Flyway · springdoc-openapi（Swagger UI）。

## 一、对账链路与核心语义

```
来源批次(CRF/LAB/IMAGING/MONITORING)
  └─ 受理仓 source_batch / raw_fact      载荷摘要sha256、事件时间、接收时间、来源修订；批次幂等
       └─ 关联器 link_candidate/fact_link 中心映射+方案窗口 → 候选+置信理由；多候选不擅自分配
            └─ 协调器 reconciled_fact(+history) 统一字段四态：CONFIRMED/CONFLICT/MISSING/PENDING
                 ├─ 质疑 query_item/query_response 责任角色、回复证据、按事实版本关闭、源再更正自动重开
                 └─ 快照 analysis_snapshot(+rows)  只消费过质量门槛的协调结果；物化可复现
```

核心业务规则（与验收一一对应）：

| 规则 | 实现 |
| --- | --- |
| 互斥检验结果不按接收先后覆盖 | 同一(受试者,访视,字段)存在多个有效互斥值 → `CONFLICT`，双方修订关系保留（`reconciled_fact_source` 角色 CONFLICTING），确认值为空，阻止进入分析 |
| 仅日期精度且窗口跨边界 | 关联器为每个命中窗口生成候选（MEDIUM 置信+理由），状态 `PENDING_DECISION`，**不擅自分配**，待人工 `/api/link-candidates/{id}/confirm` 裁定 |
| 质疑回复后源事实再更正 | 新修订取代旧修订（保留 SUPERSEDED 关系）→ 协调版本跃迁 → 已关闭/已回复质疑自动 `REOPENED`；旧回复仍引用其 `factVersionAtReply` / `sourceSigAtReply` |
| 未映射事实保持待判 | 字段目录外的来源字段不丢弃不猜测：`UNMAPPED` → 协调数据集发布 `PENDING`（`UNMAPPED::来源::字段`），并自动开质疑 |
| 人工裁定的失效 | `MANUAL_RESOLUTION` 裁定值只在来源修订签名不变时有效；源再更正后自动失效，回到证据重算 |
| 分析门槛 | 快照按 `allowedStatuses`（默认仅 CONFIRMED）物化行；CONFLICT/MISSING/PENDING 计入排除统计 |
| 旧快照复现 | 协调历史追加式存储并带全局协调序号；`/api/snapshots/{id}/verify` 以快照序号为界重放同一门槛与物化行比对 |
| 字段级回溯 | `/api/reconciled-facts/{id}/trace` 给出：协调规则码/版本、全部历史版本、来源批次（载荷摘要、接收时间、修订、SUPERSEDED 链）、关联质疑 |

## 二、建模策略取舍（按来源种类与变更频率选择）

本题两种路线都能产出有来源依据的对账数据：

- **专用适配器 → 共同事件模型（本服务采用）**：每类来源按自己的字段编码上送
  （`sourceFieldCode` 保留来源语义），受理时经**字段目录**映射到统一字段键。
  优点：保留来源语义、协调/快照查询直接、运行快；代价：每类来源一个适配契约，
  来源改版时需逐个升级（本服务中体现为更新 `field_catalog.source_field_map` 映射，
  目录外字段自动落入待判，不会静默错配）。
- **宽泛统一事件表 + 查询时按字段目录解释**：新增来源容易，但中心差异（编码、精度、
  窗口）被推到每次判读时处理，歧义与误判风险高。

当前来源仅 4 类且字段稳定、变更频率低，专用适配器的维护成本可控，换来协调规则的
确定性与可审计性；若未来接入高频改版的中心自有系统，可对该来源单独采用宽表落地、
再由目录解释接入同一协调器。**无论哪条路线，未能映射的事实必须保持待判**——这一点
由协调器统一保证，与适配策略无关。

## 三、靶病灶纵向数据集（第二数据链路）

### 3.1 链路与分层

```
影像检查 imaging_scan（设备/层厚/增强等协议元数据）+ 影像序列 imaging_series
  └─ 靶病灶登记 target_lesion        基线登记部位、测量轴、影像序列、研究受试者（BASELINE/NEW/DERIVED）
       └─ 随访阅片 reading_session(+版本) → lesion_measurement  每次测量；不可测给原因、新发现打 isNew
            └─ 映射候选 lesion_link_candidate   解剖/序列/轮廓约束打分，自动生成 + 置信理由
                 └─ 确认映射 lesion_link          人工确认/驳回或自动确认；分裂(一父多子)/融合(多父一子)
                      └─ 映射运行 lesion_mapping_run（按受试者的全局序号）
                           └─ 指标版本 lesion_metric(+history 追加式)
                                SLD 总和、相对基线/最低值变化%、可判定状态、不可比较标记
                                └─ 疗效数据集 endpoint_dataset(+rows) 物化扫描/阅片版本/映射依据，可回放复算
```

### 3.2 核心业务规则（与验收一一对应）

| 规则 | 实现 |
| --- | --- |
| 未确认候选不得入正式总和 | 随访可测测量若无确认映射，计入 `UNCONFIRMED_EXCLUDED` 并排除出 SLD；候选的高置信唯一关系才自动确认，平分/中低置信留阅片员复核 |
| 分裂保留父子且基线直径不重复比较 | 同一父病灶确认到本次多个测量 → 关系 `SPLIT`，子病灶各计一次；父病灶基线直径只在基线出现一次，不与子病灶重复相加 |
| 融合多父归一且只计一次 | 多个父病灶确认到同一测量 → 关系 `FUSION`，该测量在本次 SLD 中只计一次，两条父边都保留 |
| 新发现病灶分别记录 | 先以 `origin=NEW` 登记、测量 `isNew=true`；关系 `NEW` 无父，不并入靶病灶 SLD，打 `NEW_LESION`，供统计团队派生进展 |
| 缺值不记零 | 不可测病灶直径必须留空并给 `nonMeasurableReason`；接口拒绝给 0 直径的不可测测量与无直径的可测测量 |
| 协议越界标不可比较而非进展 | 层厚超方案阈值（默认 3.0mm，可配）或显式 `PROTOCOL_DEVIATION` → 扫描 `comparableToBaseline=false`；保留原始测量和，但相对基线/最低值变化置空、`NOT_EVALUABLE`，绝不判进展，且不更新最低值 |
| 指标版本化、只重算受影响受试者 | 更正阅片或映射只触发该受试者的重算；内容变化的指标行版本跃迁并写追加历史，无内容变化不产生新版本（jsonb 规范化后比较） |
| 旧终点可回放 | 疗效数据集物化时锚定指标版本与映射序号；`/verify` 以该序号从追加历史重放同一门槛比对，并列出创建后版本跃迁的受影响受试者 |
| 证据链 | `/api/lesion-metrics/{id}/trace` 给出指标全部版本历史、扫描协议、阅片版本、确认映射依据（含决定人与理由）及逐次测量 |

### 3.3 两种映射路线的取舍（服务同一纵向测量）

- **阅片员逐次确认**：解释性强、能处理改名/分裂/融合等形态改变，但扫描量大时人工负担高、中心间一致性需监控。
  入口：`POST /api/lesion-links/candidates/{id}/confirm|reject` 或无候选时 `POST /api/lesion-links/manual`。
- **自动受约束候选 + 人工复核**：先以解剖位置、影像序列、测量轮廓为约束打分生成候选（同部位才入候选，
  相似位置不等于身份事实），唯一高置信自动确认，低置信/平分留复核。效率高，但依赖图像元数据完整。
  提交阅片即自动执行；候选理由（加分/扣分）可在 `GET /api/lesion-links/candidates` 查看。
- **共同铁律**：未经确认的候选一律不参与正式 SLD。选择依据扫描数量与元数据质量，两条路线产出同一纵向测量。

### 3.4 演示场景（`POST /api/demo/lesion-seed`，幂等）

| 受试者 | 场景 | 关键断言 |
| --- | --- | --- |
| SUBJ-001 | 持续缩小 + 随访新病灶 | 基线 50 → 45(-10%) → 39(-22%)；新病灶 S1-TL03 不入 SLD；相对最低值 -13.3% |
| SUBJ-002 | 病灶分裂 | 父病灶 40 分裂为 25+20=45（+12.5%），自动 SPLIT 两条边，父直径不重复比较 |
| SUBJ-003 | 两病灶融合 | 25+15=40 融合为单一病灶 30（-25%），自动+人工确认，FUSION 两条边只计一次 |
| SUBJ-004 | 协议越界 + 不可测 + 未确认门控 | 厚层 5mm 扫描变化置空标不可比较；骨病灶伪影不可测缺值非零；低置信候选未确认前排除（35 而非 53） |

### 3.5 手工验收（应用运行中）

```bash
curl -X POST http://localhost:8080/api/demo/lesion-seed -H 'X-User: rad1' -H 'X-Role: RAD'
curl 'http://localhost:8080/api/lesion-metrics?subjectKey=SUBJ-002'                 # 分裂指标
curl 'http://localhost:8080/api/lesion-metrics/timeline?subjectKey=SUBJ-002'        # 病灶时间线
curl 'http://localhost:8080/api/lesion-links/candidates?status=CANDIDATE'           # 待复核候选+打分理由
# 确认 SUBJ-004 的低置信候选（取 id 最大者，指向最近一次复查）
CID=$(curl -s 'http://localhost:8080/api/lesion-links/candidates?status=CANDIDATE' \
  | python3 -c "import sys,json;print(max(c['id'] for c in json.load(sys.stdin) if c['toLesionKey']=='S4-TL32'))")
curl -X POST "http://localhost:8080/api/lesion-links/candidates/$CID/confirm" -H 'X-User: rad1' -H 'X-Role: RAD'
curl -X POST http://localhost:8080/api/endpoint-datasets -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' --data-binary @src/main/resources/samples/lesions/endpoint-dataset.json
curl http://localhost:8080/api/endpoint-datasets/1/verify                          # 旧终点可回放
# 阅片更正（旧版本 SUPERSEDED，仅重算 SUBJ-001）
curl -X POST 'http://localhost:8080/api/readings/correct?subjectKey=SUBJ-001&scanKey=S1-FU1' \
  -H 'Content-Type: application/json' -H 'X-User: rad1' -H 'X-Role: RAD' \
  --data-binary @src/main/resources/samples/lesions/lesion-reading-correction.json
```

## 四、一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x：/v3/api-docs）
- 数据库：PostgreSQL 18（compose 内 `db` 服务，库/账号/密码均为 `recon`），Flyway 自动建表

装载演示数据：

```bash
curl -X POST http://localhost:8080/api/demo/seed            # 多源事实对账演示
curl -X POST http://localhost:8080/api/demo/lesion-seed -H 'X-User: rad1' -H 'X-Role: RAD'  # 靶病灶六类场景
```

## 五、本地开发

预装运行时：JDK 25（`/opt/jdk-25`）、Maven 3.9（`/opt/maven`）、PostgreSQL 18（`127.0.0.1:5432`，库/用户/密码 `ccdev`）。

```bash
# 建库（首次）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c "create database recon_dev;"

# 启动（Flyway 自动建表）
DB_URL=jdbc:postgresql://127.0.0.1:5432/recon_dev DB_USER=ccdev DB_PASSWORD=ccdev \
  /opt/maven/bin/mvn spring-boot:run

# 装载演示种子（幂等，可重复）
curl -X POST http://localhost:8080/api/demo/seed
```

## 六、测试账号（演示身份头，非安全机制）

请求头 `X-User` / `X-Role` 标识操作人与责任角色，写入审计字段（裁定人、回复人、关闭人）：

| 账号 | 角色 | 职责 |
| --- | --- | --- |
| `dm1` | DM | 数据管理：冲突裁定、未映射字段处理、快照与疗效数据集制作 |
| `cra1` | CRA | 临床监查：访视关联裁定、监查发现与缺失跟进 |
| `lab1` | LAB | 中心实验室：检验结果与更正批次上送 |
| `rad1` / `rad2` | RAD | 独立阅片员：影像检查/病灶登记、随访阅片、映射候选确认与阅片更正 |
| `pi1` | PI | 主要研究者：质疑复核 |
| `admin` | ADMIN | 默认/管理 |

质疑责任角色自动分派：冲突/未映射 → DM；缺失/关联歧义/监查发现 → CRA。
靶病灶域操作人由 `X-User`/`X-Role` 写入阅片、确认映射与指标重算的审计字段（如自动映射记 `system:auto-mapper`）。

## 七、验证方式（以下均已实际执行通过）

```bash
./verify.sh
```

脚本依次执行并断言（全部 PASS）：

1. `mvn test` —— 真实 PostgreSQL `recon_test` 库上的 5 个验收集成测试：
   - `AcceptanceFlowTest`（2 用例，多源事实对账）：事实匹配、冲突保留、质疑闭环（更正自动重开）、
     人工裁定失效、分析门槛、旧快照复现、字段级回溯、批次幂等与旧修订 STALE
   - `LesionAcceptanceTest`（3 用例，靶病灶纵向）：
     - 六类场景：持续/新病灶（NEW 不入 SLD）、分裂（父子不重复比较）、融合（多父只计一次）、
       不可测（缺值非零）、协议越界（不可比较非进展）、未确认候选门控
     - 测量集合与变化比例：SLD 总和、相对基线/最低值百分比、`contributingLesionIds`、各场景 flags
     - 版本与回放：人工确认后指标转 EVALUABLE 并版本跃迁；阅片更正仅重算受影响受试者、旧疗效数据集按序号回放一致
     - 录入校验：不可测给 0 直径/可测无直径 → 400、未登记病灶 → 404、重复扫描 → 409
2. 打包并真实启动应用（`recon_dev` 库），HTTP 冒烟 16 项断言：
   - 对账域 7 项：冲突保留 / 跨边界候选 / 未映射待判 / 分析门槛 / 质疑重开 / 旧快照复现 / 字段级回溯
   - 靶病灶域 9 项：分裂不重复比较 / 新病灶不入 SLD / 融合只计一次 / 协议越界不可比较且缺值非零 /
     未确认门控 / 疗效数据集物化复算 / 确认后跃迁旧终点回放 / 更正仅重算受影响受试者 / 缺值记零拦截

> 说明：Docker 镜像构建未在本开发容器内实测（容器无 docker 守护进程）；
> Dockerfile 使用与本地完全相同的 Maven 3.9 + JDK 25 构建链与启动命令，本地启动与全部验证均按上文实际执行通过。

## 八、演示数据与手工验收流程

样例批次（`src/main/resources/samples/`，可直接 POST 到 `/api/batches`）：

| 文件 | 来源 | 场景 |
| --- | --- | --- |
| crf-batch-1.json | CRF | S-001/V2 收缩压120；S-002/V1 血压两项（显式访视，自动关联） |
| lab-batch-1.json | 检验 | S-001 两条互斥 ALT：35（仅日期，单窗口自动关联）与 52（显式 V2）→ 冲突；S-002 ALT 41 |
| imaging-batch-1.json | 影像 | 检查日期 2026-03-19 仅日期精度，落在 V2[13,18] 与 V3[18,31] 边界 → 双候选待裁定 |
| monitoring-batch-1.json | 监查 | SDV 不一致发现 → 自动开质疑（CRA） |
| crf-batch-2-unmapped.json | CRF | 目录外字段 XXMOOD → 未映射待判 |
| lab-batch-2.json | 检验 | 两条 ALT 均更正为 36（rev2）→ 演示修订取代与质疑重开 |

主数据：试验 ONCO-2026-01；受试者 SUBJ-001/002（C01）、SUBJ-003（C02）；
访视窗口 V1[-2,2]、V2[13,18]、V3[18,31]（V2/V3 第 18 天相接）；
字段目录 6 项（4 项分析必需）。

靶病灶样例（`src/main/resources/samples/lesions/`，配合 3.5 节手工验收）：

| 文件 | 用途 |
| --- | --- |
| `lesion-scan.json` / `lesion-scan-protocol-deviation.json` | 常规检查登记 / 层厚 5mm 越界检查（自动判不可比较） |
| `lesion-new-lesion.json` | 随访新发现病灶登记（origin=NEW，测量 isNew=true，不入 SLD） |
| `lesion-reading.json` / `lesion-reading-nonmeasurable.json` | 分裂阅片（两子代测量）/ 含不可测病灶的阅片（缺值非零+原因） |
| `lesion-reading-correction.json` | 阅片更正（旧版本 SUPERSEDED，仅重算该受试者） |
| `endpoint-dataset.json` | 疗效数据集创建载荷（默认门槛：仅 EVALUABLE 且可比较） |

手工验收（应用运行中）：

```bash
curl -X POST http://localhost:8080/api/demo/seed          # 重置+装载
curl 'http://localhost:8080/api/reconciled-facts?status=CONFLICT'      # 冲突保留
curl 'http://localhost:8080/api/link-candidates?status=CANDIDATE'      # 跨边界双候选
curl 'http://localhost:8080/api/reconciled-facts?status=PENDING'       # 待判（未映射+未裁定）
curl 'http://localhost:8080/api/reconciled-facts?status=MISSING'       # 缺失
curl -X POST http://localhost:8080/api/snapshots -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"name":"IA-1"}'                # 质量门槛快照
curl http://localhost:8080/api/snapshots/1/rows                        # 快照行（仅CONFIRMED）
# 质疑闭环：回复→关闭→上送更正批次→观察 REOPENED
QID=$(curl -s 'http://localhost:8080/api/queries?status=OPEN' | python3 -c "import sys,json;print([q['id'] for q in json.load(sys.stdin) if q['issueType']=='CONFLICT'][0])")
curl -X POST http://localhost:8080/api/queries/$QID/responses -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"responseText":"以复查值为准","evidenceRef":"memo-1"}'
curl -X POST http://localhost:8080/api/queries/$QID/close -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"resolution":"完成"}'
curl -X POST http://localhost:8080/api/batches -H 'Content-Type: application/json' \
  -H 'X-User: lab1' -H 'X-Role: LAB' --data-binary @src/main/resources/samples/lab-batch-2.json
curl http://localhost:8080/api/queries/$QID                            # status=REOPENED
curl http://localhost:8080/api/snapshots/1/verify                      # reproducible=true
curl http://localhost:8080/api/reconciled-facts/1/trace                # 字段级回溯
```

## 九、API 一览

| 域 | 端点 |
| --- | --- |
| 受理仓 | `POST /api/batches`、`GET /api/batches`、`GET /api/raw-facts` |
| 关联器 | `POST /api/linking/run`、`GET /api/link-candidates`、`POST /api/link-candidates/{id}/confirm|reject`、`GET /api/links` |
| 协调器 | `POST /api/reconcile/run`、`GET /api/reconciled-facts`、`GET /api/reconciled-facts/{id}/trace`、`POST /api/reconciled-facts/{id}/resolve`、`GET /api/reconcile/status` |
| 质疑 | `GET /api/queries`、`GET /api/queries/{id}`、`POST /api/queries/{id}/responses`、`POST /api/queries/{id}/close` |
| 快照 | `POST /api/snapshots`、`GET /api/snapshots`、`GET /api/snapshots/{id}/rows`、`GET /api/snapshots/{id}/verify` |
| 主数据 | `GET /api/subjects`、`GET /api/center-map`、`GET /api/visit-schedule`、`GET /api/field-catalog` |
| 影像登记 | `POST /api/imaging/scans`、`GET /api/imaging/scans`、`POST /api/imaging/lesions`、`GET /api/imaging/lesions` |
| 随访阅片 | `POST /api/readings`、`POST /api/readings/correct`、`GET /api/readings` |
| 病灶映射 | `GET /api/lesion-links/candidates`、`POST /api/lesion-links/candidates/{id}/confirm|reject`、`POST /api/lesion-links/manual`、`GET /api/lesion-links` |
| 纵向指标 | `GET /api/lesion-metrics`、`GET /api/lesion-metrics/timeline`、`GET /api/lesion-metrics/{id}/trace`、`GET /api/lesion-metrics/runs` |
| 疗效数据集 | `POST /api/endpoint-datasets`、`GET /api/endpoint-datasets`、`GET /api/endpoint-datasets/{id}/rows`、`GET /api/endpoint-datasets/{id}/verify` |
| 演示 | `POST /api/demo/seed`、`POST /api/demo/lesion-seed`、`POST /api/demo/reset` |

## 十、工程结构

```
├── Dockerfile / docker-compose.yml / docker/maven-settings.xml
├── verify.sh                         # 一键验证（测试+真实启动+HTTP断言，LF 行尾）
├── src/main/resources/db/migration/
│   ├── V1__init.sql                              # 多源对账表结构（Flyway）
│   └── V2__target_lesion_longitudinal.sql        # 靶病灶纵向域 12 张表
├── src/main/resources/samples/
│   ├── *.json                       # 四类来源样例批次（对账域）
│   └── lesions/*.json               # 靶病灶扫描/阅片/新病灶/更正/疗效数据集样例
├── src/main/java/com/example/clinicalrecon/
│   ├── api/        # 12 个 REST 控制器（7 对账 + 5 靶病灶），camelCase 输出统一
│   ├── service/    # 对账：Intake/Linking/Reconcile/Query/Snapshot/DemoSeed
│   │                # 靶病灶：Imaging/Reading/LesionLinking/LesionMetric/Endpoint/LesionDemoSeed
│   ├── repo/       # JdbcTemplate 数据访问（对账 6 聚合 + 靶病灶 5 聚合）
│   ├── domain/     # 枚举常量（Enums）、DTO（Dtos / LesionDtos）
│   └── config/     # OpenAPI、身份头过滤器、异常映射
└── src/test/java/.../
    ├── AcceptanceFlowTest.java      # 多源对账验收集成测试（2 用例）
    └── LesionAcceptanceTest.java    # 靶病灶纵向验收集成测试（3 用例）
```
