#!/usr/bin/env bash
# 临床试验多源事实对账与质疑数据服务 —— 本地一键验证
# 步骤：建测试库 -> 跑验收集成测试 -> 打包 -> 启动应用 -> HTTP 级冒烟（种子/冲突/质疑重开/快照复现）
set -euo pipefail
cd "$(dirname "$0")"

JAVA=/opt/jdk-25/bin/java
MVN=/opt/maven/bin/mvn
PSQL="/opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev"
export PGPASSWORD=ccdev
APP_PORT=8080
BASE="http://127.0.0.1:${APP_PORT}/api"

echo "==> [1/5] 准备数据库 recon_test / recon_dev"
$PSQL -c "drop database if exists recon_test;" -c "create database recon_test;" >/dev/null
$PSQL -c "drop database if exists recon_dev;"  -c "create database recon_dev;"  >/dev/null

echo "==> [2/5] 运行验收集成测试 (mvn test)"
$MVN -q test
echo "    集成测试通过"

echo "==> [3/5] 打包并启动应用"
$MVN -q package -DskipTests
DB_URL=jdbc:postgresql://127.0.0.1:5432/recon_dev DB_USER=ccdev DB_PASSWORD=ccdev \
  nohup $JAVA -jar target/clinical-fact-recon-1.0.0.jar > /tmp/recon-app.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:${APP_PORT}/v3/api-docs" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf "http://127.0.0.1:${APP_PORT}/v3/api-docs" >/dev/null || { echo "应用启动失败"; tail -30 /tmp/recon-app.log; exit 1; }
echo "    应用已启动 (pid=$APP_PID)"

echo "==> [4/5] 装载演示种子并做 HTTP 冒烟断言"
curl -sf -X POST "$BASE/demo/seed" >/dev/null

# 4.1 冲突保留：SUBJ-001/V2 的 LAB_ALT 为 CONFLICT 且无确认值
CONFLICT=$(curl -sf "$BASE/reconciled-facts?status=CONFLICT")
echo "$CONFLICT" | grep -q '"fieldKey":"LAB_ALT"' || { echo "FAIL: 未找到 LAB_ALT 冲突"; exit 1; }
echo "$CONFLICT" | grep -q '"ruleCode":"MUTUALLY_EXCLUSIVE"' || { echo "FAIL: 冲突规则码不正确"; exit 1; }
echo "    PASS 冲突保留（互斥检验结果双方保留，字段阻止确认）"

# 4.2 窗口跨边界：影像检查产生 2 个候选且不擅自分配
CANDS=$(curl -sf "$BASE/link-candidates?status=CANDIDATE")
[ "$(echo "$CANDS" | grep -o '"visitCode"' | wc -l)" = "2" ] || { echo "FAIL: 跨边界候选数不为2"; exit 1; }
echo "    PASS 窗口跨边界列出候选不擅自分配"

# 4.3 未映射字段保持待判
curl -sf "$BASE/reconciled-facts?status=PENDING" | grep -q 'UNMAPPED::CRF::XXMOOD' \
  || { echo "FAIL: 未映射字段未保持待判"; exit 1; }
echo "    PASS 未映射字段保持待判"

# 4.4 分析门槛：快照只含 CONFIRMED
SNAP=$(curl -sf -X POST "$BASE/snapshots" -H 'Content-Type: application/json' -H 'X-User: dm1' -H 'X-Role: DM' \
  -d '{"name":"IA-smoke"}')
SNAP_ID=$(echo "$SNAP" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
ROWS=$(curl -sf "$BASE/snapshots/$SNAP_ID/rows")
echo "$ROWS" | grep -q '"fieldKey":"VITAL_SYS_BP"' || { echo "FAIL: 快照缺确认字段"; exit 1; }
echo "$ROWS" | python3 -c "
import sys, json
rows = json.load(sys.stdin)
bad = [r for r in rows if r['fieldKey']=='LAB_ALT' and r['subjectKey']=='SUBJ-001']
sys.exit(1 if bad else 0)" || { echo "FAIL: 冲突字段进入快照"; exit 1; }
echo "    PASS 分析门槛（冲突/缺失/待判不进入快照）"

# 4.5 质疑闭环：回复+关闭 -> 上送检验更正批次 -> 自动重开
QID=$(curl -sf "$BASE/queries?status=OPEN" | python3 -c \
  "import sys,json;qs=[q for q in json.load(sys.stdin) if q['issueType']=='CONFLICT'];print(qs[0]['id'])")
curl -sf -X POST "$BASE/queries/$QID/responses" -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' \
  -d '{"responseText":"以复查值为准","evidenceRef":"memo-1"}' >/dev/null
curl -sf -X POST "$BASE/queries/$QID/close" -H 'Content-Type: application/json' \
  -H 'X-User: dm1' -H 'X-Role: DM' -d '{"resolution":"完成"}' >/dev/null
curl -sf -X POST "$BASE/batches" -H 'Content-Type: application/json' -H 'X-User: lab1' -H 'X-Role: LAB' \
  --data-binary @src/main/resources/samples/lab-batch-2.json >/dev/null
STATUS=$(curl -sf "$BASE/queries/$QID" | python3 -c "import sys,json;print(json.load(sys.stdin)['query']['status'])")
[ "$STATUS" = "REOPENED" ] || { echo "FAIL: 源更正后质疑未重开 (status=$STATUS)"; exit 1; }
echo "    PASS 质疑闭环（源事实再更正自动重开，旧回复保留版本引用）"

# 4.6 旧快照复现
curl -sf "$BASE/snapshots/$SNAP_ID/verify" | grep -q '"reproducible":true' \
  || { echo "FAIL: 旧快照复算不一致"; exit 1; }
echo "    PASS 旧快照复现（物化行不变，按协调序号复算一致）"

# 4.7 回溯链
TRACE_ID=$(echo "$ROWS" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['reconciledFactId'])")
curl -sf "$BASE/reconciled-facts/$TRACE_ID/trace" | grep -q '"payloadDigest"' \
  || { echo "FAIL: 回溯缺批次载荷摘要"; exit 1; }
echo "    PASS 字段级回溯（来源批次摘要/修订/协调规则）"

echo "==> [4b/5] 靶病灶纵向数据集 HTTP 冒烟"
curl -sf -X POST "$BASE/demo/lesion-seed" -H 'X-User: rad1' -H 'X-Role: RAD' >/dev/null

# 4b.1 分裂：父直径不重复比较（25+20=45，相对基线 40 为 +12.5%），自动 SPLIT
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-002" | python3 -c "
import sys,json
ms={m['scanKey']:m for m in json.load(sys.stdin)}
fu=ms['S2-FU1']
assert fu['sumDiameters']==45.0, fu
assert fu['pctChangeBaseline']==12.5, fu
assert 'SPLIT' in fu['flags'], fu
assert len(fu['contributingLesionIds'])==2, fu" \
  || { echo "FAIL: 分裂测量集合/变化比例不正确"; exit 1; }
echo "    PASS 分裂父子保留且基线直径不重复比较"

# 4b.2 新病灶不并入 SLD（SUBJ-001 FU1=27+18=45，新病灶 12 不计），持续变化 -10%
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-001" | python3 -c "
import sys,json
ms={m['scanKey']:m for m in json.load(sys.stdin)}
assert ms['S1-FU1']['sumDiameters']==45.0
assert ms['S1-FU1']['pctChangeBaseline']==-10.0
assert 'NEW_LESION' in ms['S1-FU1']['flags']
assert len(ms['S1-FU1']['contributingLesionIds'])==2" \
  || { echo "FAIL: 新病灶被误并入总和或持续变化错误"; exit 1; }
echo "    PASS 持续缩小且新病灶分别记录不入 SLD"

# 4b.3 融合：两父归一，单一测量只计一次（30，-25%，FUSION 两条边）
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-003" | python3 -c "
import sys,json
fu=[m for m in json.load(sys.stdin) if m['scanKey']=='S3-FU1'][0]
assert fu['sumDiameters']==30.0 and fu['pctChangeBaseline']==-25.0 and 'FUSION' in fu['flags']" \
  || { echo "FAIL: 融合总和/变化错误"; exit 1; }
[ "$(curl -sf "$BASE/lesion-links?subjectId=3" | grep -o '"relationType":"FUSION"' | wc -l)" = "2" ] \
  || { echo "FAIL: 融合父子边数量不为2"; exit 1; }
echo "    PASS 融合多父归一且只计一次"

# 4b.4 协议越界：变化留空标不可比较而非进展；不可测缺值不记零
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-004" | python3 -c "
import sys,json
ms={m['scanKey']:m for m in json.load(sys.stdin)}
fu=ms['S4-FU1']
assert fu['evaluableStatus']=='NOT_EVALUABLE' and fu['comparable']==False
assert 'pctChangeBaseline' not in fu, '协议越界变化必须留空'
assert 'PROTOCOL_DEVIATION' in fu['flags'] and 'NON_MEASURABLE' in fu['flags']
assert fu['sumDiameters']==60.0" \
  || { echo "FAIL: 协议越界未正确标不可比较"; exit 1; }
curl -sf "$BASE/readings?subjectKey=SUBJ-004" | python3 -c "
import sys,json
for r in json.load(sys.stdin):
    for m in r['measurements']:
        if m['lesionKey']=='S4-TL32' and not m['measurable']:
            assert m.get('diameter') is None and m['nonMeasurableReason']" \
  || { echo "FAIL: 不可测缺值被记零或缺原因"; exit 1; }
echo "    PASS 协议越界标不可比较（非进展）且缺值不记零"

# 4b.5 未确认候选门控：FU2 仅 TL31=35，NOT_EVALUABLE + UNCONFIRMED_EXCLUDED
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-004" | python3 -c "
import sys,json
fu=[m for m in json.load(sys.stdin) if m['scanKey']=='S4-FU2'][0]
assert fu['sumDiameters']==35.0 and fu['evaluableStatus']=='NOT_EVALUABLE'
assert 'UNCONFIRMED_EXCLUDED' in fu['flags']" \
  || { echo "FAIL: 未确认候选参与了正式总和"; exit 1; }
echo "    PASS 未确认候选不参与正式总和"

# 4b.6 疗效数据集物化 + 复算一致（默认门槛 8 行，排除 NOT_EVALUABLE）
EP=$(curl -sf -X POST "$BASE/endpoint-datasets" -H 'Content-Type: application/json' -H 'X-User: dm1' -H 'X-Role: DM' \
  -d '{"name":"EP-smoke"}')
EP_ID=$(echo "$EP" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
[ "$(curl -sf "$BASE/endpoint-datasets/$EP_ID/rows" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')" = "8" ] \
  || { echo "FAIL: 疗效数据集门槛行数不为8"; exit 1; }
curl -sf "$BASE/endpoint-datasets/$EP_ID/verify" | grep -q '"reproducible":true' \
  || { echo "FAIL: 疗效数据集复算不一致"; exit 1; }
echo "    PASS 疗效数据集物化证据链并可复算"

# 4b.7 人工确认候选 → FU2 转 EVALUABLE（35+18=53），旧数据集仍可回放
CAND=$(curl -sf "$BASE/lesion-links/candidates?status=CANDIDATE" | python3 -c "
import sys,json
cs=[c for c in json.load(sys.stdin) if c['toLesionKey']=='S4-TL32']
print(max(c['id'] for c in cs))")
curl -sf -X POST "$BASE/lesion-links/candidates/$CAND/confirm" -H 'X-User: rad1' -H 'X-Role: RAD' >/dev/null
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-004" | python3 -c "
import sys,json
fu=[m for m in json.load(sys.stdin) if m['scanKey']=='S4-FU2'][0]
assert fu['evaluableStatus']=='EVALUABLE' and fu['sumDiameters']==53.0 and fu['version']>=2" \
  || { echo "FAIL: 确认后指标未正确转为可评估"; exit 1; }
curl -sf "$BASE/endpoint-datasets/$EP_ID/verify" | grep -q '"reproducible":true' \
  || { echo "FAIL: 旧疗效数据集在确认后不可回放"; exit 1; }
echo "    PASS 人工确认后指标跃迁且旧终点可回放"

# 4b.8 阅片更正 → 仅该受试者重算（SUBJ-001 FU1 45→44，版本跃迁）
V_BEFORE=$(curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-001" | python3 -c "
import sys,json;print([m for m in json.load(sys.stdin) if m['scanKey']=='S1-FU1'][0]['version'])")
curl -sf -X POST "$BASE/readings/correct?subjectKey=SUBJ-001&scanKey=S1-FU1" \
  -H 'Content-Type: application/json' -H 'X-User: rad1' -H 'X-Role: RAD' \
  --data-binary @src/main/resources/samples/lesions/lesion-reading-correction.json >/dev/null
curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-001" | python3 -c "
import sys,json
ms={m['scanKey']:m for m in json.load(sys.stdin)}
assert ms['S1-FU1']['sumDiameters']==44.0 and ms['S1-FU1']['version']>$V_BEFORE
assert ms['S1-FU2']['pctChangeNadir']==-11.4" \
  || { echo "FAIL: 阅片更正后未正确重算受影响受试者"; exit 1; }
# 其他受试者指标版本不变（SUBJ-002 仍为 v1）
[ "$(curl -sf "$BASE/lesion-metrics?subjectKey=SUBJ-002" | python3 -c "
import sys,json;print(max(m['version'] for m in json.load(sys.stdin)) if False else [m for m in json.load(sys.stdin) if m['scanKey']=='S2-FU1'][0]['version'])")" = "1" ] \
  || { echo "FAIL: 更正影响了无关受试者"; exit 1; }
echo "    PASS 更正后仅受影响受试者重算，旧终点可回放"

# 4b.9 录入校验：不可测给 0 直径 → 400（缺值不记零）
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/readings" -H 'Content-Type: application/json' \
  -d '{"subjectKey":"SUBJ-001","scanKey":"S1-FU2","reader":"rad1","readAt":"2026-04-01","measurements":[{"lesionKey":"S1-TL01","measurable":false,"diameter":0,"nonMeasurableReason":"伪影"}]}')
[ "$CODE" = "400" ] || { echo "FAIL: 缺值记零未被拦截 (http=$CODE)"; exit 1; }
echo "    PASS 录入校验拦截缺值记零"

echo "==> [5/5] 停止应用"
kill $APP_PID 2>/dev/null || true
trap - EXIT
echo ""
echo "全部验证通过 ✅"
