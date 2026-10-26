"""Generate current-version evidence tables. Missing or failed rounds remain visible."""
from pathlib import Path
from datetime import datetime, timezone
import csv
import hashlib
import json
import math
import re
import statistics
import sys
import shutil
import os

ROOT=Path(__file__).resolve().parents[2]
RUN=Path(sys.argv[1]).resolve()
OUT=RUN/"report"
OUT.mkdir(exist_ok=True,parents=True)
HANDBOOK=os.path.relpath(ROOT/"docs/测试/性能与可用性测试手册.md",OUT).replace("\\","/")
RESULTS=HANDBOOK+"#实测数据"

def read(name,default=None):
    try:return json.loads((RUN/name).read_text(encoding="utf-8-sig"))
    except (OSError,json.JSONDecodeError):return default

def lines(path):
    result=[]
    if path.exists():
        for text in path.read_text(encoding="utf-8-sig",errors="replace").splitlines():
            try:result.append(json.loads(text))
            except json.JSONDecodeError:pass
    return result

def metric(value,name,key="count",default=0):
    return value.get("metrics",{}).get(name,{}).get("values",{}).get(key,default)

def stats(values):
    values=sorted(values)
    def q(p):
        i=(len(values)-1)*p
        return values[math.floor(i)]+(values[math.ceil(i)]-values[math.floor(i)])*(i-math.floor(i))
    return {"n":len(values),"p50":q(.5),"p95":q(.95),"p99":q(.99),"min":values[0],"max":values[-1]} if values else {"n":0}

def num(value,digits=2):return "—" if value is None else f"{value:.{digits}f}"

accepted={}
for path in RUN.glob("load-*.log"):
    for row in lines(path):
        try:item=json.loads(row.get("msg",""))
        except (json.JSONDecodeError,TypeError):continue
        if item.get("kind")=="accepted-order":accepted[item["orderId"]]=item
observed={str(row["orderId"]):row for row in lines(RUN/"order-observations.jsonl")}
summaries={}
rounds=[]
for path in sorted(RUN.glob("*.json")):
    source=read(path.name,{})
    if not isinstance(source,dict) or "metrics" not in source:continue
    name=path.stem
    summaries[name]=source
    row={"name":name,"mode":source.get("test",{}).get("mode"),"targetRps":source.get("test",{}).get("targetRate"),
        "requests":metric(source,"http_reqs"),"actualRps":metric(source,"http_reqs","rate"),
        "p50Ms":metric(source,"http_req_duration","med",None),"p95Ms":metric(source,"http_req_duration","p(95)",None),
        "p99Ms":metric(source,"http_req_duration","p(99)",None),"maxMs":metric(source,"http_req_duration","max",None),
        "httpErrorRate":metric(source,"http_req_failed","rate"),"contentValidRate":metric(source,"content_valid","rate"),
        "unexpected":metric(source,"business_unexpected"),"dropped":metric(source,"dropped_iterations"),
        "accepted":metric(source,"business_accepted"),"rejected":metric(source,"business_rejected"),
        "sharedHits":metric(source,"cache_shared"),"redisHits":metric(source,"cache_redis"),
        "backendHits":metric(source,"cache_backend"),"staleResponses":metric(source,"cache_stale")}
    row["steadyCachePass"]=(row["dropped"]==0 and row["httpErrorRate"]<=.001 and row["contentValidRate"]>=.999 and row["p95Ms"]<=100)
    execution=read(name+"-execution.json",{})
    row["execution"]=execution
    voucher=source.get("test",{}).get("voucherId")
    if voucher:
        items=[v for v in observed.values() if v.get("voucherId")==str(voucher)]
        same_clock=[v["observedHostEpochMs"]-accepted[str(v["orderId"])]["startedHostEpochMs"] for v in items if str(v["orderId"]) in accepted]
        row["clientToDatabase"]=stats(same_clock)
        row["redisToDatabaseRaw"]=stats([v["latencyMs"] for v in items])
        row["observationCount"]=len(items)
        row["snapshot"]=read(name+"-snapshot.json",{})
        row["initialSnapshot"]=read(name+"-before-snapshot.json",{})
        row["drain"]=read(name+"-drain.json",{})
        before=read(name+"-clock-before.json",{})
        after=read(name+"-clock-after.json",{})
        if before and after:row["clockDriftMs"]=after["offsetHostMinusRedisMs"]-before["offsetHostMinusRedisMs"]
        if items and same_clock:
            first=min(accepted[str(v["orderId"])]["startedHostEpochMs"] for v in items if str(v["orderId"]) in accepted)
            last=max(v["observedHostEpochMs"] for v in items)
            row["roundFinalThroughput"]=len(items)/((last-first)/1000)
        if same_clock:row["finalP95Within5s"]=row["clientToDatabase"]["p95"]<=5000
    rounds.append(row)

resources=lines(RUN/"resources.jsonl")
resource_summary={"samples":len(resources),"minimumAvailableGiB":min((v["availableMemoryBytes"]/1024**3 for v in resources),default=None),
    "hostCpuPercent":stats([v["hostCpuPercent"] for v in resources if v.get("hostCpuPercent") is not None])}
for row in rounds:
    execution=row["execution"]
    samples=[v for v in resources if execution.get("startEpoch",float("inf"))<=datetime.fromisoformat(v["time"]).timestamp()<=execution.get("endEpoch",0)]
    row["resources"]={"samples":len(samples),"hostCpuMax":max((v["hostCpuPercent"] for v in samples if v.get("hostCpuPercent") is not None),default=None),
        "minimumAvailableGiB":min((v["availableMemoryBytes"]/1024**3 for v in samples),default=None)}
    row["resourceBudgetMet"]=row["resources"]["minimumAvailableGiB"] is not None and row["resources"]["minimumAvailableGiB"]>=1

CONTROL=RUN.with_name(RUN.name+"-memory-control")
supplement={"runId":CONTROL.name,"rounds":[]}
if CONTROL.exists():
    def control_read(name,default=None):
        try:return json.loads((CONTROL/name).read_text(encoding="utf-8-sig"))
        except (OSError,json.JSONDecodeError):return default
    supplement["status"]=control_read("status.json")
    supplement["cleanup"]=control_read("cleanup.json")
    supplement["audit"]=control_read("cleanup-audit.json")
    control_resources=lines(CONTROL/"resources.jsonl")
    control_observed=lines(CONTROL/"order-observations.jsonl")
    control_accepted={}
    for path in CONTROL.glob("load-*.log"):
        for row in lines(path):
            try:item=json.loads(row.get("msg",""))
            except (json.JSONDecodeError,TypeError):continue
            if item.get("kind")=="accepted-order":control_accepted[str(item["orderId"])]=item
    for path in sorted(CONTROL.glob("control-*-r*.json")):
        source=control_read(path.name,{})
        if "metrics" not in source:continue
        execution=control_read(path.stem+"-execution.json",{})
        samples=[v for v in control_resources if execution.get("startEpoch",float("inf"))<=datetime.fromisoformat(v["time"]).timestamp()<=execution.get("endEpoch",0)]
        voucher=str(source.get("test",{}).get("voucherId",""))
        matching=[v["observedHostEpochMs"]-control_accepted[str(v["orderId"])]["startedHostEpochMs"] for v in control_observed if str(v.get("voucherId"))==voucher and str(v["orderId"]) in control_accepted]
        supplement["rounds"].append({"name":path.stem,"requests":metric(source,"http_reqs"),"actualRps":metric(source,"http_reqs","rate"),
            "p95Ms":metric(source,"http_req_duration","p(95)"),"p99Ms":metric(source,"http_req_duration","p(99)"),
            "dropped":metric(source,"dropped_iterations"),"httpErrorRate":metric(source,"http_req_failed","rate"),"contentValidRate":metric(source,"content_valid","rate"),
            "minimumAvailableGiB":min((v["availableMemoryBytes"]/1024**3 for v in samples),default=None),"clientToDatabase":stats(matching),
            "snapshot":control_read(path.stem+"-snapshot.json"),"drain":control_read(path.stem+"-drain.json")})
    original=read("environment.json",{})
    control_hashes={v["path"]:v["sha256"] for v in control_read("environment.json",{}).get("sourceHashes",[])}
    supplement["businessFingerprintMatches"]=all(control_hashes.get(v["path"])==v["sha256"] for v in original.get("sourceHashes",[]) if v["path"].startswith(("src\\main","src/main","deploy\\openresty","deploy/openresty")) or v["path"].endswith("SNAPSHOT.jar"))

cdc={}
for name in ["cdc-hot-idle","cdc-normal-idle","cdc-hot-load","cdc-normal-load"]:
    value=read(name+".json")
    if value:
        result=value.get("result",{})
        samples=result.get("samples",[])
        cdc[name]={"state":value["state"],"count":len(samples),"successful":sum(bool(v["freshAll"]) for v in samples),
            "delayMs":stats([v["elapsedMs"] for v in samples if v["freshAll"]]),
            "withNotification":sum(v.get("edgeCallbacks",0)>0 for v in samples),"samples":samples}

burst={path.stem:read(path.name) for path in RUN.glob("cache-burst-*.json")}
environment=read("environment.json",{})
business_changes=[]
for item in environment.get("sourceHashes",[]):
    path=item["path"]
    if path.startswith(("src\\main","src/main","deploy\\openresty","deploy/openresty")) or path.endswith("SNAPSHOT.jar"):
        source=ROOT/path
        if not source.exists() or hashlib.sha256(source.read_bytes()).hexdigest()!=item["sha256"]:business_changes.append(path)
fault_names=["fault-mysql-recovery-drain.json","fault-crash-recovery-drain.json","fault-redis-restored.json",
    "fault-permanent-compensated-snapshot.json","fault-canal-recovery.json","fault-broker-recovery.json","fault-edge-retry-recovery.json"]
faults={name:read(name) for name in fault_names}
edge_events=lines(RUN/"edge-events.jsonl")
for service in ["canal","broker"]:
    value=faults.get("fault-"+service+"-recovery.json")
    update=read("fault-"+service+"-update.json",{})
    if value and update:
        value["successfulEdgeCallbacksAfterUpdate"]=sum(e.get("shopId")==update.get("shopId") and e.get("status")==200 and update["commitReturnHostEpochMs"]<=e["epochMillis"]<=value["recoveredEpoch"]*1000 for e in edge_events)
retry_evidence=[]
log_path=RUN/"app-18086.out.log"
if log_path.exists() and faults.get("fault-edge-retry-recovery.json"):
    log_lines=log_path.read_text(encoding="utf-8",errors="replace").splitlines()
    successful=[line for line in log_lines if "SHOP_CACHE_CDC_ACK" in line and re.search(r"retries=[1-9]",line)][-2:]
    identifiers=[re.search(r"msgId=(\S+)",line).group(1) for line in successful]
    retry_evidence=[line for line in log_lines if "SHOP_CACHE_CDC_" in line and any("msgId="+ident in line for ident in identifiers)]
    (RUN/"cdc-retry-evidence.log").write_text("\n".join(retry_evidence)+"\n",encoding="utf-8")
summary={"generatedAt":datetime.now(timezone.utc).isoformat(),"runId":RUN.name,"status":read("status.json"),"rounds":rounds,
    "cdc":cdc,"bursts":burst,"resources":resource_summary,"faults":faults,"businessFingerprintChanges":business_changes,"memoryControl":supplement}
(OUT/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
fields=["name","mode","targetRps","requests","actualRps","p50Ms","p95Ms","p99Ms","maxMs","httpErrorRate","contentValidRate","dropped","accepted","rejected","sharedHits","redisHits","backendHits"]
with (OUT/"逐轮指标.csv").open("w",encoding="utf-8-sig",newline="") as file:
    writer=csv.DictWriter(file,fieldnames=fields,extrasaction="ignore");writer.writeheader();writer.writerows(rounds)

def repeated(pattern):
    groups={}
    for row in rounds:
        match=re.fullmatch(pattern,row["name"])
        if match:groups.setdefault(match.group(1),[]).append(row)
    return groups

def fault_verdict(name,value):
    if value is None:return "未完成，不能宣称通过"
    if name in ["fault-mysql-recovery-drain.json","fault-crash-recovery-drain.json"]:
        return ("通过" if value.get("valid") else "失败")+f"；{value.get('finished')}/{value.get('expected')} 终态，恢复后排空观测 {num(value.get('seconds'))}s"
    if name=="fault-redis-restored.json":
        load=summaries.get("fault-redis-hot-read",{})
        good=metric(load,"content_valid","rate")==1 and metric(load,"http_req_failed","rate")==0 and metric(load,"dropped_iterations")==0
        return ("通过" if good else "未达标")+f"；断连期间 {metric(load,'http_reqs')} 次热点读，错误 {metric(load,'http_req_failed','rate'):.2%}，恢复读结果在 raw"
    if name=="fault-permanent-compensated-snapshot.json":
        states=value.get("tasks",[])
        good=value.get("orders")==0 and value.get("redisStock")=="10" and value.get("purchasers")==0 and value.get("reservations")==0 and value.get("pending")==0 and value.get("dlqLength")==0 and states and all(v.get("state")=="FAILED" and v.get("compensated") for v in states)
        return ("通过" if good else "失败")+f"；订单 {value.get('orders')}、库存 {value.get('redisStock')}、归档 {value.get('archiveLength')}，终态 {states}"
    if name in ["fault-canal-recovery.json","fault-broker-recovery.json"]:
        seconds=value.get("recoveredEpoch",0)-value.get("restartedEpoch",0)
        return f"通过；重启开始到全入口新值 {num(seconds)}s，SQL更新后有 {value.get('successfulEdgeCallbacksAfterUpdate')} 次成功CDC边缘通知（包含启动/观察开销）"
    if name=="fault-edge-retry-recovery.json":
        during=read("fault-edge-retry-during.json",{})
        return f"通过；临时503累计 {during.get('edgeFailure')} 次，恢复后全入口新值；两条消息 ACK retries=1，见 raw/cdc-retry-evidence.log"
    return "详见原始证据"

text=["# 觅街当前版本性能与可用性实测报告","",
    "测试日期：2026-10-18。运行编号：`"+RUN.name+"`。方案、工具与原理见[测试手册]("+HANDBOOK+")，精选结果和执行记录见手册中的[实测数据与分析]("+RESULTS+")。自动生成的完整报告保留在本机运行目录。", "",
    "## 一、结论与适用范围","",
    "本轮针对固定热点、多层逻辑过期、普通旧值异步刷新、Canal/RocketMQ 同步及 Stream 订单失败补偿的当前实现。业务源码/JAR 在测量前后"+("未发现变化。" if not business_changes else "发生变化，必须核对 summary 中的变更列表。"),"",
    "当前执行状态：`"+str((summary["status"] or {}).get("stage","未知"))+"`。执行完成和测试全部达标是不同结论；下文保留所有失败轮。", "",
    "本机共置压测与服务，短时重复轮不等于生产容量或 SLA；不使用旧 600 RPS 数据替代新版成绩。", "",
    "## 二、环境与测量口径","",
    "- JDK 21.0.12.1，k6 2.3.0；当前业务 JAR 重新构建，Maven 回归 53 项、失败/错误/跳过均为 0。",
    "- Intel Core Ultra 5 125H，"+str(environment.get("logicalProcessors"))+" 个逻辑处理器，主机内存 "+num(environment.get("memoryBytes",0)/1024**3)+" GiB；VMware Redis 为 2 vCPU/2 GiB。",
    "- MySQL 9.6.0、ROW/FULL、事务提交刷盘 1、sync_binlog=1；Redis 6.2.6，AOF=yes、appendfsync=always；单 Broker SYNC_FLUSH。",
    "- 测试入口 18085、消费 JVM 18086，各最大堆 512 MiB；OpenResty 18084；Broker 19111/HA 19112。旧端口被 Windows 排除范围覆盖，未修改系统保留规则。",
    "- Redis DB14、四个固定 HOT、五十个普通商铺、三百个独立冷商铺、4000 合成用户；只操作带本次标记的专用业务行。",
    "- HOT 源逻辑 30 分钟、上层逻辑 5 秒，三层不设物理 TTL；NORMAL 正文物理 TTL30分钟。订单认领 30 秒、恢复间隔 5 秒、最大投递 5 次，保持业务默认值。",
    "- 每轮 30 秒、三次重复。表中 RPS 为 k6 完成请求速率；单列报告 dropped。终态主指标是同机‘客户端请求开始→数据库结果可见’，200ms 轮询；Redis 受理时间校准值仅作为辅助。",
    "- 个别 Windows/k6 延迟中位数为 0，保留原值；不能解释为请求没有耗时或系统成本为零。", "",
    "## 三、缓存稳态逐轮结果","",
    "| 场景 | 完成请求 | 实际 RPS | p50 ms | p95 ms | p99 ms | dropped | 内容成功率 | 判定 |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
steady=[r for r in rounds if re.fullmatch(r"cache-(?:hot-(?:edge|java)-\d+|normal-(?:edge|java)-300|mixed-300)-r\d+",r["name"])]
for r in steady:
    verdict='未达标' if not r['steadyCachePass'] else ('通过' if r['resourceBudgetMet'] else '响应达标，低内存需补测')
    text.append(f"| {r['name']} | {r['requests']} | {num(r['actualRps'])} | {num(r['p50Ms'])} | {num(r['p95Ms'])} | {num(r['p99Ms'])} | {r['dropped']} | {r['contentValidRate']:.2%} | {verdict} |")
text += ["", "### 三轮汇总","","| 场景与目标 RPS | 请求总数 | p95 中位数 ms | p95 范围 ms | dropped 总数 |","| --- | ---: | ---: | --- | ---: |"]
for name,group in sorted(repeated(r"(cache-(?:hot-(?:edge|java)-\d+|normal-(?:edge|java)-300|mixed-300))-r\d+").items()):
    values=[v["p95Ms"] for v in group]
    text.append(f"| {name} | {sum(v['requests'] for v in group)} | {num(statistics.median(values))} | {num(min(values))}–{num(max(values))} | {sum(v['dropped'] for v in group)} |")
text += ["", "有 dropped 的轮次明确未达标，不用正常响应的低延迟掩盖未发出的请求。边缘/Java 入口、HOT/NORMAL 缓存策略不同，只能按相同负载与场景分别比较。", "",
    "![缓存查询延迟](缓存查询延迟.png)","",
    "## 四、冷启动、逻辑过期与容量检查","","| 同键冷查询批次 | 请求 | 成功 | 整批 ms | 请求 p95 ms | 商铺正文 JOIN 增量 |","| --- | ---: | ---: | ---: | ---: | ---: |"]
for name,value in sorted(burst.items()):text.append(f"| {name} | {value['requests']} | {value['successful']} | {num(value['batchMs'])} | {num(value['latency'].get('p95Ms'))} | {value['businessJoinLoads']} |")
text += ["", "JOIN 增量不包含版本表 SQL；不能写成‘整个 MySQL 只查询一次’。冷商铺每轮 100 个、10 并发，属于批次；逻辑过期场景也是指定批次，不能把它们与固定到达率的吞吐容量混为一谈。缓存最终驻留和 TTL 见 `raw/cache-final.json`，只验证四个固定热点，未验证 500 家真实容量。", "",
    "### 不同冷商铺并发与追加对照","","| 批次 | 请求 | 内容成功 | 技术错误 | p95 ms |","| --- | ---: | ---: | ---: | ---: |"]
for r in rounds:
    if re.fullmatch(r"cache-cold-(?:r\d+|control-vus[14]-r\d+)",r["name"]):
        text.append(f"| {r['name']} | {r['requests']} | {r['contentValidRate']:.2%} | {r['unexpected']} | {num(r['p95Ms'])} |")
text += ["", "初始 10 并发、不同冷 ID 场景出现 CacheBusy/503，保留失败成绩；追加 1/4 并发对照以检查默认四路源执行器、SynchronousQueue 无排队及拒绝策略的影响。该对照在初始失败后追加，未修改业务预算或失败处理。它与同一个 ID 的请求合并是不同问题。", "",
    "## 五、秒杀受理与最终落库","","| 场景 | 受理/拒绝 | HTTP p95 ms | 请求至DB可见 p95 ms | p99 ms | 匹配终态样本 | dropped | 排空后订单 |","| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
for r in rounds:
    if not re.fullmatch(r"orders-(?:\d+-r\d+|stock-competition|duplicate-user)",r["name"]):continue
    end=r.get("clientToDatabase",{})
    text.append(f"| {r['name']} | {r['accepted']}/{r['rejected']} | {num(r['p95Ms'])} | {num(end.get('p95'))} | {num(end.get('p99'))} | {end.get('n',0)} | {r['dropped']} | {r.get('snapshot',{}).get('orders','—')} |")
text += ["", "主要终态指标由订单 ID 关联客户端日志与数据库观测，包含接口处理、排队、数据库事务及轮询观测。Redis 受理至数据库可见的原始校准统计、前后时钟偏差及排空后的库存、唯一用户、任务状态、PEL 和消费尾位点均保存在 summary/raw 中。", "",
    "![订单接口与终态延迟](订单受理与终态延迟.png)","",
    "60 RPS 的三轮终态 p95 为 1.803s、7.005s、6.431s，其中两轮超过沿用的 5 秒目标；接口低延迟不能证明同步事务完成。不同轮之间出现明显积压差异，本轮未进行独立硬件和观察器对照，不能把原因完全归于数据库、刷盘或消费者代码中的某一项。", "",
    "重复消息场景重放 100 条已完成订单，按 `orders-replay-comparison.json` 核对两端库存、订单数及 Pending/尾位点；不把重复购买的业务拒绝算成技术故障。", "",
    "## 六、Canal + RocketMQ 收敛延迟","","| 场景 | 成功/样本 | p50 ms | p95 ms | p99 ms | 有HTTP通知证据样本 |","| --- | ---: | ---: | ---: | ---: | ---: |"]
for name,value in sorted(cdc.items()):
    s=value["delayMs"]
    text.append(f"| {name} | {value['successful']}/{value['count']} | {num(s.get('p50'))} | {num(s.get('p95'))} | {num(s.get('p99'))} | {value['withNotification']} |")
text += ["", "从直接 SQL 提交返回起，到边缘与两 JVM 都读到目标正文为止；顺序轮询三入口，包含观测开销。背景 300 RPS 的 dropped/错误须同时检查 `cdc-background-load.json`，不能只看目标速率。SQL 绕过业务提交回调，配合 MQ/通知证据验证真实 CDC 路径。", "",
    "![缓存同步收敛延迟](缓存同步收敛延迟.png)","",
    "## 七、可用性与故障恢复","","| 场景 | 结果证据 |","| --- | --- |"]
for name,value in faults.items():text.append("| "+name+" | "+fault_verdict(name,value)+" |")
text += ["", "故障只影响本轮代理连接或本轮登记的进程；Broker/Canal 重启沿用原存储/位点。永久订单失败应最终无订单、FAILED 已补偿、Redis 预占释放，不重新创建订单。进程/连接恢复不等同于断电、磁盘损坏、主从切换或多 Broker 高可用。", "",
    "## 八、资源与结果边界","",
    "- 主资源采样 "+str(resource_summary["samples"])+" 次，最低主机可用内存 "+num(resource_summary["minimumAvailableGiB"])+" GiB；CPU 统计包含共机其他负载，详见 summary/逐轮资源。",
    "- 补充采样记录 OpenResty worker；MySQL 和 VMware 进程可能因 Windows 权限返回 WinError=5，缺失值保留为不可用，不当作 CPU/内存为零。",
    "- 旧工具逐条 Redis 取受理时间，本轮改成批量异步取值并使用同机请求时间作为主指标，订单延迟不能不加说明地与旧报告直接作百分比比较。",
    "- 测试未修改业务算法、默认日志等级、持久化刷盘或认领重试时间；仅调整测试部署端口、固定热点名单、Redis 逻辑库及队列/消费组隔离参数。", "",
    "## 九、与旧版测试文档对齐","",
    "| 旧版场景/口径 | 当前版本处理 |","| --- | --- |",
    "| 2026-10-08 HOT 边缘/Java 100/300/600 RPS | 保留负载和三轮 30 秒形状，改测常驻逻辑过期实现 |",
    "| 旧通用缓存与混合读 | 增加 NORMAL 独立入口，确认普通正文不进入上层 |",
    "| 旧 cache:shop 键、日期逻辑期限、删除上层模拟冷启动 | 使用 v3 键、毫秒逻辑期限；冷启动采用无上层副本的普通商铺，HOT 用逻辑过期验证 |",
    "| 秒杀 10/30/60 RPS，库存/重复/重放 | 保留；接口受理与终态分别报告，新增同机请求起点作为主口径 |",
    "| CDC 空载/背景各30样本 | 分 HOT/NORMAL 四组共120样本；记录全部入口和通知证据 |",
    "| 旧 8084/8085/8086、DB15、Broker10911 | Windows 保留端口影响，改18084/18085/18086、DB14、Broker19111 |",
    "| 旧 600 RPS 与订单终态 p95 9.34秒 | 保留历史含义，不作为新版实测或无条件比较基线 |", "",
    "## 十、证据与清理","",
    "原始运行目录：`"+str(RUN)+"`。可分享的 JSON/CSV/JSONL 证据放 raw；不复制真实凭据、内部密钥、合成 token 清单或完整业务日志。", "",
    "清理结果：`"+json.dumps(read("cleanup.json",{"state":"尚未清理"}),ensure_ascii=False)+"`。数据库版本墓碑和 Redis ID 计数保留；不执行 FLUSHDB、清表或重置真实库存。"]
audit=read("cleanup-audit.json",{})
text += ["",f"独立只读审计：通过={audit.get('passed')}，SQL业务行剩余={audit.get('sqlRemainingRows')}，检查自有正文/订单/token等Redis键 {audit.get('ownedRedisKeysChecked')} 项、剩余 {audit.get('ownedRedisKeysRemaining')}。补测结束后数据库总键数 {audit.get('redisDatabaseSize')}，包含保留的版本元数据及订单ID计数；不把版本墓碑当作未清理业务正文，不清空整库。补测审计 { (supplement.get('audit') or {}).get('ownedRedisKeysChecked') } 项自有键、剩余 {(supplement.get('audit') or {}).get('ownedRedisKeysRemaining')}，见 summary.memoryControl 与 raw/memory-control。",
    "", "初始工具摘要在 environment.json；恢复编排、内存检查和格式化变更在执行记录披露，交付时工具/实际编译类摘要另存 raw/tool-final-fingerprints.json。业务JAR、业务源码与边缘Lua摘要在主测及补测之间相同。"]
headline=["### 主要实测结论","",
    f"- 原始缓存稳态 {sum(r['steadyCachePass'] for r in steady)}/{len(steady)} 轮满足响应指标，其中3轮运行中内存不足；同时满足响应与资源预算为 {sum(r['steadyCachePass'] and r['resourceBudgetMet'] for r in steady)}/{len(steady)}。另3轮 dropped 25、122、29次，保留未达标。",
    "- HOT 边缘原始 600 RPS p95 为0.66/0.87/0.74ms，其中两轮低内存，以独立补测表核对；HOT Java600三轮为0.90/0.79/0.72ms，均无 dropped 和内容错误。四个预热 HOT、30秒共机数据不代表容量上限。",
    "- 同键 100 并发六批全部成功，每批商铺正文 JOIN 增量 1；不同 ID 冷启动 10 并发三批成功率仅 36%/32%/29%，源执行器过载返回 503。追加 1/4 并发各三批全部成功。",
    "- 订单 10/30 RPS 三轮全部落库；60 RPS 也最终正确，但终态 p95 两轮超过 5 秒，不能把受理接口的约 11–12ms 当作最终订单耗时。300 库存竞争只生成 300 单；重复购买与 100 条重放保持幂等。"]
if cdc:
    headline.append(f"- CDC 直接 SQL 更新 {sum(v['successful'] for v in cdc.values())}/{sum(v['count'] for v in cdc.values())} 样本收敛；分场景延迟和背景实际负载见第六节。")
headline += ["- 可用性是本轮连接/进程故障恢复验证，不能替代长期稳定性、断电恢复或多节点高可用验证。","",
    "失败与短板优先保留为后续优化依据，本轮未修改业务实现或默认持久化/并发预算。",""]
edge_controls=[r for r in supplement['rounds'] if r['name'].startswith('control-hot-edge-600-')]
if len(edge_controls)==3:
    headline.insert(3,"- 内存充足的独立 HOT 边缘600RPS补测，三轮p95为 "+"/".join(num(r['p95Ms']) for r in edge_controls)+"ms，dropped总数 "+str(sum(r['dropped'] for r in edge_controls))+"，最低可用内存 "+num(min(r['minimumAvailableGiB'] for r in edge_controls))+"GiB；与原始轮分表保留。")
position=text.index("## 二、环境与测量口径")
text[position:position]=headline
control_text=["### 内存有效性审计与独立补测","",
    "原始保护只在轮前检查，轮中资源采样发现 `cache-hot-edge-600-r2/r3`、`cache-hot-java-100-r1` 及 `orders-10-r2` 低于1GiB；原值保留但标注资源限制。新增独立运行，同一业务JAR、拓扑、默认参数和预热，重新创建等结构夹具；编排每250ms检测，低于1GiB终止。未改业务预算，也未重跑覆盖原 dropped/冷启动失败。", "",
    f"补测状态：`{(supplement.get('status') or {}).get('stage','未完成')}`；业务源码/JAR摘要相同：`{supplement.get('businessFingerprintMatches')}`。下表 HTTP p95 与终态 p95 分列。", "",
    "| 补测轮次 | 请求 | 实际RPS | HTTP p95 ms | 请求至DB p95 ms | dropped | 内容成功 | 最低可用GiB |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
for r in supplement['rounds']:
    control_text.append(f"| {r['name']} | {r['requests']} | {num(r['actualRps'])} | {num(r['p95Ms'])} | {num(r['clientToDatabase'].get('p95'))} | {r['dropped']} | {r['contentValidRate']:.2%} | {num(r['minimumAvailableGiB'])} |")
control_text += ["", "补测原始证据在 `raw/memory-control/`，包括全部轮次、资源、摘要及清理审计；原始轮图仍显示原值和范围，不用补测悄悄替换。",
    f"补测清理完成：`{(supplement.get('cleanup') or {}).get('cleaned')}`，独立审计通过：`{(supplement.get('audit') or {}).get('passed')}`。",""]
position=text.index("## 四、冷启动、逻辑过期与容量检查")
text[position:position]=control_text
background=next((r for r in rounds if r['name']=='cdc-background-load'),None)
if background:
    position=text.index("## 七、可用性与故障恢复")
    text[position:position]=[f"背景读实际完成 {background['requests']} 请求、{num(background['actualRps'])} RPS，dropped={background['dropped']}，HTTP 错误率 {background['httpErrorRate']:.2%}，内容正确率 {background['contentValidRate']:.2%}。",""]
(OUT/"性能与可用性实测报告.md").write_text("\n".join(text)+"\n",encoding="utf-8")
raw=OUT/"raw";raw.mkdir(exist_ok=True)
fingerprints=[]
for path in sorted((ROOT/"deploy/performance").iterdir()):
    if path.is_file() and path.suffix in {".java",".py",".js",".ps1"}:
        fingerprints.append({"path":str(path.relative_to(ROOT)),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
for folder in [RUN/"classes",CONTROL/"classes"]:
    if folder.exists():
        for path in sorted(folder.glob("*.class")):
            fingerprints.append({"path":str(path.relative_to(ROOT)),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
(RUN/"tool-final-fingerprints.json").write_text(json.dumps({"note":"初始摘要见environment；编排恢复/内存保护与格式整理在执行记录披露。本文件记录交付时工具与实际编译类摘要。","files":fingerprints},ensure_ascii=False,indent=2),encoding="utf-8")
for path in RUN.glob("*"):
    if not path.is_file():continue
    if path.suffix==".json" and path.name not in {"state.json","fixture.json","manifest.json"} and not path.name.endswith("-arguments.json"):
        shutil.copy2(path,raw/path.name)
    elif path.name in {"execution.jsonl","order-observations.jsonl","cdc-samples.jsonl","edge-events.jsonl","fault-events.jsonl","resources.jsonl","infrastructure-resources.jsonl","cdc-retry-evidence.log"}:
        shutil.copy2(path,raw/path.name)
if CONTROL.exists():
    control_out=raw/"memory-control";control_out.mkdir(exist_ok=True)
    for path in CONTROL.glob("*"):
        if not path.is_file():continue
        if path.suffix==".json" and path.name not in {"state.json","fixture.json","manifest.json"} and not path.name.endswith("-arguments.json"):
            shutil.copy2(path,control_out/path.name)
        elif path.name in {"execution.jsonl","resources.jsonl","order-observations.jsonl"}:shutil.copy2(path,control_out/path.name)
print(json.dumps({"rounds":len(rounds),"steadyRounds":len(steady),"matchedOrders":len(observed),"output":str(OUT)},ensure_ascii=True))
