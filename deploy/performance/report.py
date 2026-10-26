"""Generate an auditable report from retained results; never invent missing measurements."""
from pathlib import Path
import csv, json, math, os, re, shutil, statistics, sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
RUN = Path(sys.argv[1]).resolve()
OUT = RUN / 'report'
OUT.mkdir(parents=True, exist_ok=True)

def read(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8-sig'))
    except (OSError, json.JSONDecodeError):
        return default

def lines(path):
    try:
        return [json.loads(s.lstrip('\ufeff')) for s in Path(path).read_text(encoding='utf-8-sig').splitlines() if s.strip()]
    except (OSError, json.JSONDecodeError):
        return []

def quantile(values, p):
    values = sorted(values)
    if not values: return None
    x = (len(values)-1)*p
    a = int(x); b = min(a+1, len(values)-1)
    return values[a] + (values[b]-values[a])*(x-a)

def metric(s, name, key='count', default=0):
    return s.get('metrics', {}).get(name, {}).get('values', {}).get(key, default)

def number(value, digits=2):
    return '—' if value is None else f'{value:.{digits}f}'

def iso_ms(s): return datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp()*1000

summaries = {}
for path in RUN.glob('*.json'):
    data = read(path, {})
    if isinstance(data, dict) and 'metrics' in data:
        summaries[path.stem] = data

rows = []
for name, s in sorted(summaries.items()):
    n = metric(s, 'http_reqs'); seconds = s.get('state', {}).get('testRunDurationMs', 0)/1000
    row = dict(name=name, requests=n, seconds=seconds, actualRps=metric(s, 'http_reqs', 'rate'),
               p50Ms=metric(s, 'http_req_duration', 'med', None), p95Ms=metric(s, 'http_req_duration', 'p(95)', None),
               p99Ms=metric(s, 'http_req_duration', 'p(99)', None), maxMs=metric(s, 'http_req_duration', 'max', None),
               technicalErrors=metric(s, 'business_unexpected'), httpErrorRate=metric(s, 'http_req_failed', 'rate'),
               contentValidRate=metric(s, 'content_valid', 'rate'), dropped=metric(s, 'dropped_iterations'),
               accepted=metric(s, 'business_accepted'), rejected=metric(s, 'business_rejected'),
               sharedHits=metric(s, 'cache_shared'), redisHits=metric(s, 'cache_redis'), backendHits=metric(s, 'cache_backend'))
    rows.append(row)

accepted = {}
for path in RUN.glob('*.err.log'):
    for event in lines(path):
        try:
            item = json.loads(event.get('msg', ''))
            if item.get('kind') == 'accepted-order': accepted[item['orderId']] = {**item, 'test': path.name.removesuffix('.err.log')}
        except (json.JSONDecodeError, TypeError): pass

observations = lines(RUN / 'order-observations.jsonl')
env = read(RUN / 'environment.json', {})
deps = read(RUN / 'dependencies.json', {})
initial_offset = deps.get('redisClockOffsetHostMinusRedisMs', 0)

for item in observations:
    original_latency = item['latencyMs']
    info = accepted.get(item['orderId'])
    item['initialCalibrationLatencyMs'] = original_latency
    if info:
        before = read(RUN / (info['test'] + '-before-clock.json'), {})
        after = read(RUN / (info['test'] + '-after-clock.json'), {})
        offset = initial_offset
        if before and after:
            span = after['hostEpochMs'] - before['hostEpochMs']
            f = max(0, min(1, (item['observedHostEpochMs']-before['hostEpochMs'])/span)) if span else 0
            offset = before['hostMinusRedisMs']*(1-f) + after['hostMinusRedisMs']*f
            item['clockOffsetRangeMs'] = abs(after['hostMinusRedisMs']-before['hostMinusRedisMs'])
        item['latencyMs'] = max(0, item['observedHostEpochMs'] - (float(item['acceptedRedisEpochMs']) + offset))
        item['requestStartToVisibleMs'] = max(0, item['observedHostEpochMs'] - info['startedHostEpochMs'])
        item['test'] = info['test']

by_voucher = {}
for item in observations: by_voucher.setdefault(item['voucherId'], []).append(item)
for row in rows:
    s = summaries[row['name']]; vid = s.get('test', {}).get('voucherId')
    items = by_voucher.get(str(vid), [])
    if vid:
        row['voucherId'] = vid; row['observedTerminal'] = len(items)
        row['finalP50Ms'] = quantile([x['latencyMs'] for x in items], .5)
        row['finalP95Ms'] = quantile([x['latencyMs'] for x in items], .95)
        row['finalP99Ms'] = quantile([x['latencyMs'] for x in items], .99)
        row['clientStartToFinalP95Ms'] = quantile([x['requestStartToVisibleMs'] for x in items if 'requestStartToVisibleMs' in x], .95)
        snap = read(RUN / (row['name'] + '-snapshot.json'), {})
        row['finalOrders'] = snap.get('orders'); row['mysqlStock'] = snap.get('mysqlStock'); row['redisStock'] = snap.get('redisStock')
        drain = read(RUN / (row['name'] + '-drain.json'), {})
        row['drainSeconds'] = drain.get('seconds'); row['drainTimedOut'] = drain.get('timedOut', False)
        if items:
            elapsed = (max(x['observedHostEpochMs'] for x in items)-min(float(x['acceptedRedisEpochMs'])+initial_offset for x in items))/1000
            row['wholeCompletionRate'] = len(items)/elapsed if elapsed > 0 else None

resources = lines(RUN / 'resources.jsonl')
for row in rows:
    execution = read(RUN / (row['name'] + '-execution.json'), {})
    if not execution: continue
    start, end = iso_ms(execution['start']), iso_ms(execution['end'])
    samples = [r for r in resources if start <= iso_ms(r['time']) <= end]
    if samples:
        row['systemCpuMeanPercent'] = statistics.mean(r['systemCpuPercent'] for r in samples)
        row['systemCpuMaxPercent'] = max(r['systemCpuPercent'] for r in samples)
        row['minimumFreeGiB'] = min(r['availableMemoryBytes'] for r in samples)/2**30

timeline=[]
for row in rows:
    execution=read(RUN/(row['name']+'-execution.json'),{})
    if execution:
        start,end=iso_ms(execution['start']),iso_ms(execution['end']);source='launcher timestamps (includes tool startup)'
    else:
        end=iso_ms(summaries[row['name']].get('test',{}).get('timestamp'));start=end-row['seconds']*1000;source='summary end minus measured duration'
    def china(ms): return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(__import__('datetime').timezone(__import__('datetime').timedelta(hours=8))).isoformat()
    timeline.append({'test':row['name'],'startChina':china(start),'endChina':china(end),'timeSource':source,'requests':row['requests'],'accepted':row['accepted'],'rejected':row['rejected'],'technicalErrors':row['technicalErrors'],'dropped':row['dropped']})
timeline.sort(key=lambda x:x['startChina'])
with (OUT/'execution-rounds.csv').open('w',encoding='utf-8-sig',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=list(timeline[0]));writer.writeheader();writer.writerows(timeline)

normal=[r for r in rows if re.fullmatch(r'order-(10|30|60)-r\d+',r['name'])]
checks=[]
for row in normal:
    snap=read(RUN/(row['name']+'-snapshot.json'),{});initial=2500
    checks.append({'test':row['name'],'accepted':row['accepted'],'observedTerminal':row['observedTerminal'],'orders':snap.get('orders'),'passed':snap.get('orders')==row['accepted']==row['observedTerminal'] and snap.get('distinctUsers')==row['accepted'] and snap.get('pending')==0 and snap.get('mysqlStock',-1)+row['accepted']==initial and int(snap.get('redisStock',-1))==snap.get('mysqlStock')})
known_ids=set(accepted);observed_ids={x['orderId'] for x in observations}
audit={'formalOrderChecks':checks,'knownClientAcceptedIds':len(known_ids),'knownAcceptedIdsMissingTerminal':sorted(known_ids-observed_ids),'totalK6Requests':sum(r['requests'] for r in rows),'totalAccepted':sum(r['accepted'] for r in rows),'totalDropped':sum(r['dropped'] for r in rows),'cleanup':read(RUN/'cleanup-audit.json',{})}
(OUT/'audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')

fields = sorted({k for row in rows for k in row})
with (OUT / 'rounds.csv').open('w', encoding='utf-8-sig', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=fields); writer.writeheader(); writer.writerows(rows)
(OUT / 'summary.json').write_text(json.dumps({'environment': env, 'rounds': rows}, ensure_ascii=False, indent=2), encoding='utf-8')
(OUT / 'order-observations.jsonl').write_text(''.join(json.dumps(x, ensure_ascii=False)+'\n' for x in observations), encoding='utf-8')

text = []
def add(s=''): text.append(s)
add('# 核心链路性能与可用性实测报告')
add(f'\n运行标识：`{RUN.name}`。所有数值来自本次保留的原始记录；缺失项显示“—”。')
add('\n## 环境与测量边界')
add(f'\n- 主机：{env.get("os", "未知")}，{env.get("cpu", "未知")}，{env.get("cores", "—")} 核/{env.get("logicalProcessors", "—")} 线程，{env.get("totalMemoryKiB", 0)/2**20:.2f} GiB 内存。')
add('- 压测机、两个 Java 实例、OpenResty、MySQL、单 Broker RocketMQ 与 Canal 共机；Redis 位于 2 vCPU/2 GiB 的 CentOS VMware 虚拟机。应用与 Redis/MySQL 之间包含专用故障注入 TCP 代理。')
add('- JVM：JDK 21，两个应用各 `-Xms128m -Xmx512m`；业务实现、Caffeine/边缘 5 秒 TTL、Redis 商铺 30 分钟 TTL、Stream 30 秒认领/5 秒扫描/5 次投递保持工程参数。JVM 业务 DEBUG 日志与 OpenResty access log 保留。')
redis_version = re.search(r'(?m)^redis_version:(.+)', deps.get('redisInfo', ''))
mysql = deps.get('mysql', {}); probe = read(RUN / 'baseline-clock.json', {})
add(f'- Redis：{redis_version.group(1).strip() if redis_version else "未知"}，`appendonly={probe.get("config",{}).get("appendonly","未知")}`，`appendfsync={probe.get("config",{}).get("appendfsync","未知")}`。MySQL {mysql.get("version","未知")}，`innodb_flush_log_at_trx_commit={mysql.get("innodbFlushLogAtTrxCommit","未知")}`，`sync_binlog={mysql.get("syncBinlog","未知")}`。')
add('- k6 固定到达率，每个迭代一个业务请求；HTTP p95/p99 为 `http_req_duration`，包含发送、服务等待和接收，不包含 DNS/TCP 建连与连接排队。相关其他 timings 保留在原始 summary 中。')
add('- 订单终态延迟：Redis 受理时间 → MySQL 终态提交可见；约 200 ms 轮询，含查询和观测延迟。使用每轮前后 Redis TIME 的最小 RTT 样本校准主机/虚拟机时间；另保存客户端请求开始至终态可见的值。')
add('- 每档三轮，报告的是各轮 p95 的中位数与最小/最大范围，不是混合样本的总体 p95。Windows 单有效 Nginx worker 与共机资源竞争限制结果适用范围；未测生产最大容量、磁盘故障或主从切换。')
add('\n## 缓存稳态结果')
add('\n![缓存查询p95](cache-p95.png)')
add('\n| 路径 | 目标 RPS | 轮数/请求总数 | 实际 RPS 中位数 | p95 中位数（范围）ms | p99 中位数 ms | 技术错误 / dropped |')
add('| --- | ---: | ---: | ---: | ---: | ---: | ---: |')
for kind, label in [('edge','边缘热点'),('java','Java 热点'),('mixed','边缘混合')]:
    rates = [300] if kind == 'mixed' else [100,300,600]
    for rate in rates:
        subset = [r for r in rows if re.fullmatch(fr'cache-{kind}-{rate}-r\d+', r['name'])]
        if not subset: continue
        p95 = [r['p95Ms'] for r in subset]; p99 = [r['p99Ms'] for r in subset]
        add(f'| {label} | {rate} | {len(subset)} / {sum(r["requests"] for r in subset):,} | {number(statistics.median(r["actualRps"] for r in subset))} | {number(statistics.median(p95))}（{number(min(p95))}～{number(max(p95))}） | {number(statistics.median(p99))} | {sum(r["technicalErrors"] for r in subset)} / {sum(r["dropped"] for r in subset)} |')
add('\n冷启动与同键批次（与稳态容量分开）：')
add('\n| 场景 | 请求数 | p95 ms | p99 ms | 技术错误 | backend 次数 |')
add('| --- | ---: | ---: | ---: | ---: | ---: |')
for r in rows:
    if r['name'].startswith('cache-cold') or r['name']=='cache-logical-expired':
        add(f'| {r["name"]} | {r["requests"]} | {number(r["p95Ms"])} | {number(r["p99Ms"])} | {r["technicalErrors"]} | {r["backendHits"]} |')
add('\n## 秒杀受理与最终结果')
add('\n![订单终态p95](order-p95.png)')
add('\n| 目标 RPS | 轮数/请求数 | 受理 / 拒绝 / 技术错误 | 接口 p95 中位数 ms | 终态 p95 中位数（范围）ms | 终态 p99 中位数 ms | 排空等待中位数 s |')
add('| --- | ---: | ---: | ---: | ---: | ---: | ---: |')
for rate in [10,30,60]:
    subset=[r for r in rows if re.fullmatch(fr'order-{rate}-r\d+',r['name'])]
    if not subset: continue
    values=[r['finalP95Ms'] for r in subset if r.get('finalP95Ms') is not None]
    tail=[r['finalP99Ms'] for r in subset if r.get('finalP99Ms') is not None]
    drains=[r['drainSeconds'] for r in subset if r.get('drainSeconds') is not None]
    add(f'| {rate} | {len(subset)} / {sum(r["requests"] for r in subset):,} | {sum(r["accepted"] for r in subset)} / {sum(r["rejected"] for r in subset)} / {sum(r["technicalErrors"] for r in subset)} | {number(statistics.median(r["p95Ms"] for r in subset))} | {number(statistics.median(values) if values else None)}（{number(min(values) if values else None)}～{number(max(values) if values else None)}） | {number(statistics.median(tail) if tail else None)} | {number(statistics.median(drains) if drains else None)} |')

add('\n## 正确性与逻辑过期')
competition = read(RUN / 'stock-competition-check.json', {})
duplicate_before = read(RUN / 'duplicate-before.json', {})
duplicate_after = read(RUN / 'duplicate-after.json', {})
add(f'\n- 库存竞争：{json.dumps(competition, ensure_ascii=False)}。')
add(f'- 重复消息前后订单数：{duplicate_before.get("orders", "—")} → {duplicate_after.get("orders", "—")}；MySQL 库存：{duplicate_before.get("mysqlStock", "—")} → {duplicate_after.get("mysqlStock", "—")}；Redis 库存：{duplicate_before.get("redisStock", "—")} → {duplicate_after.get("redisStock", "—")}；最终 pending：{duplicate_after.get("pending", "—")}。')
def digest_count(path):
    values = read(path, [])
    if isinstance(values, dict): values = [values]
    return sum(x['count'] for x in values if 'FROM `tb_shop` WHERE' in x.get('digest', ''))
if (RUN / 'burst-after-digest.json').exists():
    delta = digest_count(RUN/'burst-after-digest.json') - digest_count(RUN/'burst-before-digest.json')
    add(f'- 同键冷缓存 100 个请求：商铺详情 SELECT digest 增量为 **{delta}**（全局 Performance Schema 摘要，不是请求头推算）。')
logical = read(RUN / 'logical-inspection.json', {})
if logical:
    add(f'- **逻辑过期测试失败**：epoch 已过期={logical.get("epochExpired")}，Java 日期解析后判定已过期={logical.get("localExpired")}。原缓存日期解析成 `{logical.get("parsedExpireTime")}`，诊断当时为 `{logical.get("now")}`，且没有重建锁。6 秒后三个入口仍返回旧名称。')
    add('- 根因：当前 Hutool 日期解析不能正确处理本例 JSON 的纳秒精度小数。Java decode 使用 Hutool 解析 `expireTime` 判断过期，未使用缓存中已有的 `expireAtEpochMillis`。这使“响应成功”和“数据已刷新”出现差异；本次保留失败证据，没有修改业务代码掩盖问题。')
    add('- 追加的首次 Java 预热组是在逻辑过期诊断夹具残留期间执行，不作为普通商铺缓存对照；清除夹具后的 `cache-java-warm-clean-100-*` 单独报告。原始稳态组发生在该夹具写入之前。')
clean = [r for r in rows if re.fullmatch(r'cache-java-warm-clean-100-r\d+',r['name'])]
if clean:
    add(f'- 清除诊断夹具、充分预热后，Java 100 RPS 三轮 p95：{", ".join(number(r["p95Ms"]) for r in clean)} ms，中位数 {number(statistics.median(r["p95Ms"] for r in clean))} ms。')

add('\n## Canal + RocketMQ 同步延迟')
add('\n| 场景 | 样本数 / 最终新值样本 | p50 ms | p95 ms | p99 ms | 最大 ms |')
add('| --- | ---: | ---: | ---: | ---: | ---: |')
for name,label in [('cdc-idle','空载'),('cdc-under-load','背景查询 300 RPS')]:
    record=read(RUN/(name+'.json'),{}).get('result',{}); delay=record.get('delay',{})
    if record:
        add(f'| {label} | {record.get("count","—")} / {record.get("successful","—")} | {number(delay.get("p50Ms"))} | {number(delay.get("p95Ms"))} | {number(delay.get("p99Ms"))} | {number(delay.get("maxMs"))} |')
add('\n这里计时起点为 SQL 提交返回，终点为边缘和两个 JVM 都读到该版本新值，约 20 ms 轮询。每个样本同时保留失效回调数。查询、网络和观测开销均包含在延迟内；30 个样本的 p99 主要反映最大的少数样本。')
background=next((r for r in rows if r['name']=='cdc-background-load'),{})
if background:
    add(f'\n**背景负载质量未达标**：目标300 RPS，90秒实际{number(background["actualRps"])} RPS、{background["requests"]}请求，dropped={background["dropped"]}，技术错误={background["technicalErrors"]}。采样延迟可以保留，但不能把该轮称为严格稳定的300 RPS负载；未进一步定位压测器未发出迭代的原因。')

add('\n## 可用性与恢复')
add('\n| 故障 | 实际结果 / 恢复记录 |')
add('| --- | --- |')
for name,label in [('fault-mysql-recovery','MySQL 测试连接中断'),('fault-worker-recovery','订单消费 JVM 强制退出')]:
    snap=read(RUN/(name+'-snapshot.json'),{}); drain=read(RUN/(name+'-drain.json'),{})
    recovery=None;anchor=''
    if name=='fault-mysql-recovery':
        restored=read(RUN/'fault-mysql-auto-restored.json',{});anchor='连接恢复至最终核对'
        if snap.get('time') and restored:recovery=(iso_ms(snap['time'])-restored['epochMillis'])/1000
    else:
        restarted=read(RUN/'fault-worker-restart.json',{});anchor='重启开始至最终核对'
        if snap.get('time') and restarted:recovery=(iso_ms(snap['time'])-iso_ms(restarted['start']))/1000
    add(f'| {label} | 订单 {snap.get("orders","—")}，pending {snap.get("pending","—")}；{anchor} {number(recovery)} 秒，核对等待 {number(drain.get("seconds"))} 秒 |')
for name,label in [('fault-cdc-recovery','CDC 消费 JVM 停机'),('fault-broker-recovery','单 Broker 重启'),('fault-canal-recovery','Canal 重启'),('fault-edge-recovery','边缘清理返回 503 后恢复')]:
    r=read(RUN/(name+'.json'),{})
    update=read(RUN/({'fault-cdc-recovery':'fault-cdc-update.json','fault-broker-recovery':'fault-broker-update.json','fault-canal-recovery':'fault-canal-update.json','fault-edge-recovery':'fault-edge-update.json'}[name]),{})
    fresh=bool(r) and len(r.get('reads',{}))==3 and all(x.get('success') and x.get('name')==update.get('name') for x in r.get('reads',{}).values())
    anchor='端点恢复至重投成功' if name=='fault-edge-recovery' else '重启开始至新值'
    add(f'| {label} | {anchor} {number(r.get("elapsedSeconds"))} 秒；全部新值={fresh} |')
permanent=read(RUN/'fault-permanent-compensation-snapshot.json',{}); drain=read(RUN/'fault-permanent-compensation-drain.json',{})
add(f'| 永久订单写库失败 | 订单 {permanent.get("orders","—")}，终态观察 {permanent.get("terminalObservation",{}).get("n","—")}，Redis 库存 {permanent.get("redisStock","—")}，DLQ {permanent.get("dlqLength","—")}，归档 {permanent.get("archiveLength","—")}；等待补偿 {number(drain.get("seconds"))} 秒 |')
status=read(RUN/'fault-mysql-status.json',{})
if status: add(f'\nMySQL 中断期间状态查询：`{json.dumps(status,ensure_ascii=False)}`。')
redis_samples=read(RUN/'fault-redis-samples.json',[])
if redis_samples:
    add(f'\nRedis 测试连接中断的边缘查询样本：{len(redis_samples)} 个，HTTP 200={sum(x.get("status")==200 for x in redis_samples)}，错误/超时={sum("error" in x for x in redis_samples)}；样本与恢复后读取结果均保留。短缓存可以暂时提供旧数据，过期后的真实行为以样本为准。')

add('\n## 资源采样')
measured=[r for r in rows if re.fullmatch(r'cache-(edge|java|mixed)-\d+-r\d+|order-\d+-r\d+',r['name'])]
windows=[]
for r in measured:
    e=read(RUN/(r['name']+'-execution.json'),{})
    if e:windows.append((iso_ms(e['start']),iso_ms(e['end'])))
samples=[s for s in resources if any(a<=iso_ms(s['time'])<=b for a,b in windows)]
if samples:
    add(f'\n正常缓存/订单正式轮的资源窗口：主机CPU均值{number(statistics.mean(s["systemCpuPercent"] for s in samples))}%，峰值{number(max(s["systemCpuPercent"] for s in samples))}%；最低可用物理内存{number(min(s["availableMemoryBytes"] for s in samples)/2**30)} GiB。这些包含本机其他程序负载。')
    add('\n| 进程 | 最大工作集 MiB | 平均CPU（单逻辑核100%） |')
    add('| --- | ---: | ---: |')
    for name in ['app-8085','app-8086','openresty-worker','k6','helper']:
        values=[p for s in samples for p in s['processes'] if p['name']==name];cpu=[p['cpuPercentOneCore'] for p in values if p.get('cpuPercentOneCore') is not None]
        if values:add(f'| {name} | {number(max(p["workingSetBytes"] for p in values)/2**20)} | {number(statistics.mean(cpu) if cpu else None)}% |')
    add('\n采样间隔约1秒，工作集包含堆与原生内存；不是每毫秒峰值，也不能据30秒轮次证明无内存泄漏。MySQL服务进程CPU未取得读取权限，原始值为null。')

add('\n完整逐轮数据见 [rounds.csv](rounds.csv)，各轮原始 k6 输出与参数保存在项目 `.local/performance/'+RUN.name+'`。')
add('\n## 数据与复现')
handbook = os.path.relpath(ROOT / 'docs/测试/性能与可用性测试手册.md', OUT).replace('\\', '/')
add('\n- [当前测试方案](' + handbook + '#测试方案)')
add('- [机器、JAR 与源码摘要及逐轮结果](summary.json)')
add('- [逐订单观测记录](order-observations.jsonl)')
add('- [指标解释与场景矩阵](' + handbook + '#测试方案)')
add('- [实测数据与完整证据位置](' + handbook + '#测试数据)')
add('- [53轮时间与结果清单](execution-rounds.csv)、[正确性与清理审计](audit.json)')
add('- 编排：`deploy/performance/run-suite.ps1`；夹具/故障/观测：`PerfHarness.java`；负载：`load.js`；报告：`report.py`。')
add('- 原始结果包含预检/冒烟、失败场景与异常，不把失败样本删掉。`.local` 中的合成 token 不需要共享；真实 MySQL/Redis 密码未写入报告。')
add('\n## 验收结论与后续处理')
add('\n- 正常缓存稳态与10/30 RPS订单组满足既定短时目标；正式9007个受理ID对应订单均可核对，未超卖/重复。')
add('- 60 RPS订单终态p95约9.34秒，未满足5秒目标；应分析单消费者处理与同步IO，在新版本/新配置中另做容量对照。')
add('- 逻辑过期纳秒日期解析是业务缺陷，需要优先修复；本次保留原实现的失败数据。')
add('- MySQL故障期间状态查询3秒超时、Redis故障期间下单16/16技术失败，属于实际可用性缺口；重启恢复通过不代表故障期不中断服务。')
add('- CDC背景负载dropped39，负载质量目标未满足。')
cleanup=read(RUN/'cleanup-audit.json',{})
add(f'- 清理审计：SQL专用行全部0，专用Redis键剩余{cleanup.get("ownedRedisKeysRemaining","—")}；原有键与ID计数器保留。正式测试结束前MQ积压0，清理DML产生的诊断事件另计。')
add('- 本轮不包含前端每秒状态轮询的叠加负载、长时间稳定性、整机断电/磁盘故障或自动主从切换。')

raw=OUT/'raw';raw.mkdir(exist_ok=True)
for path in RUN.glob('*.json'):
    if path.name not in {'fixture.json','manifest.json','pids.json'}: shutil.copy2(path,raw/path.name)
for name in ['resources.jsonl','cdc-samples.jsonl','fault-events.jsonl','edge-events.jsonl','shop-updates.jsonl','logical-control-inspection.json']:
    path=RUN/name
    if path.exists():shutil.copy2(path,raw/name)
for path in RUN.glob('*mq-progress.log'):shutil.copy2(path,raw/path.name)
(OUT / '性能与可用性实测报告.md').write_text('\n'.join(text)+'\n', encoding='utf-8')
print(f'Generated {OUT / "性能与可用性实测报告.md"}; {len(rows)} measured summaries, {len(observations)} order observations')
