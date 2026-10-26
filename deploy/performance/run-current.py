"""Current fixed-catalog performance suite. No business code or shared data is tuned.

Run with the existing Python runtime from the project root. Artifacts and exact
arguments are saved under .local/performance/<run-id>. Helpers only delete IDs
recorded in their manifest; never use FLUSHDB or table-wide deletes.
"""

from __future__ import annotations

import argparse
import ctypes as ct
from ctypes import wintypes as wt
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import threading
import time
from urllib.request import Request, urlopen
from datetime import datetime, timezone
import zipfile

PROJECT = Path(__file__).resolve().parents[2]
JAVA_HOME = Path(r"D:\JDK21\jdk-21.0.12.1+1")
JAVA = JAVA_HOME / "bin/java.exe"
K6 = next((PROJECT / ".local/performance/tools/k6").rglob("k6.exe"))
NGINX = PROJECT / ".local/openresty-1.27.1.2-win64/nginx.exe"
JAR = PROJECT / "target/hm-dianping-0.0.1-SNAPSHOT.jar"
SCRIPT = Path(__file__).parent
CONTROL = "http://127.0.0.1:19080"
EDGE = "http://127.0.0.1:18084"
APP = "http://127.0.0.1:18085"
WORKER = "http://127.0.0.1:18086"
HIDDEN = sp.CREATE_NO_WINDOW | sp.CREATE_NEW_PROCESS_GROUP
RUN: Path
STATE: dict
MONITOR_STOP = threading.Event()
LIVE: dict[str, sp.Popen] = {}


def now():
    return datetime.now(timezone.utc).isoformat()


def save(name, value):
    path = RUN / name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def event(stage, **fields):
    record = {"time": now(), "stage": stage, **fields}
    with (RUN / "execution.jsonl").open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
    save("status.json", record)
    print(json.dumps(record, ensure_ascii=True), flush=True)


def control(path, timeout=180):
    with urlopen(CONTROL + path, timeout=timeout) as response:
        return json.load(response)


def get(url, headers=None, timeout=8):
    with urlopen(Request(url, headers=headers or {}), timeout=timeout) as response:
        return json.load(response)


def port_ready(port, timeout=1):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def process_created(pid):
    """FILETIME creation identity prevents stopping a reused PID on a later phase."""
    kernel = ct.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = wt.HANDLE
    kernel.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    times = [wt.FILETIME() for _ in range(4)]
    kernel.GetProcessTimes.argtypes = [wt.HANDLE] + [ct.POINTER(wt.FILETIME)] * 4
    ok = kernel.GetProcessTimes(handle, *(ct.byref(x) for x in times))
    kernel.CloseHandle.argtypes = [wt.HANDLE]
    kernel.CloseHandle(handle)
    return ((times[0].dwHighDateTime << 32) | times[0].dwLowDateTime) if ok else None


def start(name, args, env=None, cwd=PROJECT):
    out = (RUN / (name + ".out.log")).open("ab")
    err = (RUN / (name + ".err.log")).open("ab")
    process = sp.Popen([str(x) for x in args], cwd=cwd, env=env, stdout=out, stderr=err,
                       creationflags=HIDDEN)
    out.close()
    err.close()
    LIVE[name] = process
    STATE.setdefault("processes", {})[name] = {"pid": process.pid, "created": process_created(process.pid)}
    save("state.json", STATE)
    save(name + "-arguments.json", [str(x) for x in args])
    return process


def stop(name):
    info = STATE.get("processes", {}).get(name)
    if not info:
        return
    created = process_created(info["pid"])
    if created is not None:
        if created != info["created"]:
            raise RuntimeError("PID identity changed: " + name)
        process = LIVE.get(name)
        if process:
            process.terminate()
            process.wait(timeout=15)
        else:
            kernel = ct.WinDLL("kernel32", use_last_error=True)
            kernel.OpenProcess.restype = wt.HANDLE
            handle = kernel.OpenProcess(1, False, info["pid"])
            kernel.TerminateProcess.argtypes = [wt.HANDLE, wt.UINT]
            kernel.TerminateProcess(handle, 0)
            kernel.CloseHandle.argtypes = [wt.HANDLE]
            kernel.CloseHandle(handle)
    STATE["processes"].pop(name, None)
    save("state.json", STATE)


def command(name, args, timeout=120, env=None):
    with (RUN / (name + ".log")).open("wb") as log:
        result = sp.run([str(x) for x in args], cwd=PROJECT, env=env, stdout=log,
                        stderr=sp.STDOUT, timeout=timeout, creationflags=sp.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError(name + " failed; inspect its log")


def wait_port(port, process=None, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process and process.poll() is not None:
            raise RuntimeError("Process exited before port " + str(port))
        if port_ready(port):
            return
        time.sleep(.5)
    raise TimeoutError("Port not ready: " + str(port))


class Memory(ct.Structure):
    _fields_ = [("length", wt.DWORD), ("load", wt.DWORD)] + [(x, ct.c_ulonglong) for x in
        ["total", "available", "totalPage", "availablePage", "totalVirtual", "availableVirtual", "extended"]]


def memory():
    value = Memory()
    value.length = ct.sizeof(value)
    ct.windll.kernel32.GlobalMemoryStatusEx(ct.byref(value))
    return value


def monitor():
    """Record cumulative CPU times; analysis computes deltas without spawning samplers."""
    class ProcessMemory(ct.Structure):
        _fields_ = [("size", wt.DWORD), ("faults", wt.DWORD)] + [(x, ct.c_size_t) for x in
            ["peakWorking", "working", "peakPaged", "paged", "peakNonPaged", "nonPaged", "pagefile", "peakPagefile"]]
    kernel = ct.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = wt.HANDLE
    kernel.CloseHandle.argtypes = [wt.HANDLE]
    kernel.GetProcessTimes.argtypes = [wt.HANDLE] + [ct.POINTER(wt.FILETIME)] * 4
    psapi = ct.WinDLL("psapi")
    psapi.GetProcessMemoryInfo.argtypes = [wt.HANDLE, ct.c_void_p, wt.DWORD]
    def number(value):
        return (value.dwHighDateTime << 32) | value.dwLowDateTime
    previous = None
    while not MONITOR_STOP.is_set():
        times = [wt.FILETIME() for _ in range(3)]
        kernel.GetSystemTimes(*(ct.byref(x) for x in times))
        current = [number(x) for x in times]
        cpu = None
        if previous:
            idle, kern, user = [a-b for a,b in zip(current, previous)]
            if kern+user:
                cpu = 100 * (1-idle/(kern+user))
        previous = current
        m = memory()
        processes = {}
        for name, info in list(STATE.get("processes", {}).items()):
            handle = kernel.OpenProcess(0x410, False, info["pid"])
            if not handle:
                continue
            pm = ProcessMemory()
            pm.size = ct.sizeof(pm)
            pt = [wt.FILETIME() for _ in range(4)]
            if psapi.GetProcessMemoryInfo(handle, ct.byref(pm), ct.sizeof(pm)) and kernel.GetProcessTimes(handle, *(ct.byref(x) for x in pt)):
                processes[name] = {"pid": info["pid"], "workingSetBytes": pm.working,
                    "cpuSeconds": (number(pt[2])+number(pt[3]))/1e7}
            kernel.CloseHandle(handle)
        row = {"time": now(), "hostCpuPercent": cpu, "availableMemoryBytes": m.available, "processes": processes}
        with (RUN / "resources.jsonl").open("a", encoding="utf-8") as out:
            out.write(json.dumps(row)+"\n")
        MONITOR_STOP.wait(1)


def start_app(port, consume):
    fixture = json.loads((RUN / "fixture.json").read_text(encoding="utf-8"))
    home = RUN / ("home-"+str(port))
    home.mkdir(exist_ok=True)
    args = [JAVA, "-Xms128m", "-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.home="+str(home),
        "-Xlog:gc*:file="+str(RUN / (str(port)+"-gc.log")).replace("\\", "/").replace("D:", ""),
        "-jar", JAR, "--server.port="+str(port), "--spring.redis.host=127.0.0.1",
        "--spring.redis.port=16379", "--spring.redis.database=14",
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "--hmdp.cache.hot.ids="+",".join(map(str, fixture["hotShops"])),
        "--hmdp.canal.enabled="+str(consume).lower(),
        "--hmdp.canal.consumer-group=hmdp-perf-"+RUN.name,
        "--hmdp.cache.edge-invalidate-urls="+CONTROL+"/edge",
        "--hmdp.seckill.stream-consumer.enabled="+str(consume).lower(),
        "--hmdp.seckill.stream="+fixture["stream"],
        "--hmdp.seckill.dead-letter-stream="+fixture["stream"]+":dlq",
        "--hmdp.seckill.compensation-archive="+fixture["stream"]+":archive"]
    process = start("app-"+str(port), args)
    wait_port(port, process)
    deadline = time.monotonic()+45
    while time.monotonic() < deadline:
        try:
            if get("http://127.0.0.1:"+str(port)+"/shop/"+str(fixture["hotShops"][0]))["success"]:
                return
        except Exception:
            pass
        time.sleep(.5)
    raise RuntimeError("App readiness failed")


def setup():
    event("setup")
    if (RUN / "fixture.json").exists():
        raise RuntimeError("Run already prepared")
    for port in [18084,18085,18086,13306,16379,19080,19109,19111,19112]:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", port))
    infra = RUN / "infra"
    infra.mkdir(exist_ok=True)
    broker_home = Path(r"D:\rocketmq-all-5.3.3-bin-release")
    config = (PROJECT / ".local/cache-sync/broker.conf").read_text()
    config = re.sub(r"(?m)^listenPort=.*$", "listenPort=19111", config)+"\nhaListenPort=19112\n"
    (infra / "broker.conf").write_text(config, encoding="ascii")
    args = [JAVA,"-Xms128m","-Xmx512m","-XX:MaxDirectMemorySize=256m"]
    args += ["--add-opens=java.base/"+x+"=ALL-UNNAMED" for x in ["java.lang","java.nio","sun.nio.ch","java.util"]]
    args += ["-Duser.home="+str(infra),"-Drocketmq.home.dir="+str(broker_home),"-cp",
        str(broker_home/"conf")+";"+str(broker_home/"lib/*"),"org.apache.rocketmq.broker.BrokerStartup","-c",infra/"broker.conf"]
    wait_port(19111, start("broker", args))
    command("mq-topic",[JAVA,"-Duser.home="+str(infra),"-cp",str(broker_home/"lib/*"),
        "org.apache.rocketmq.tools.command.MQAdminStartup","updateTopic","-n","127.0.0.1:9876",
        "-b","127.0.0.1:19111","-t","hmdp-shop-change","-r","4","-w","4"])
    libs = RUN / "lib"
    libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(JAR) as archive:
        for entry in archive.namelist():
            if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                (libs/Path(entry).name).write_bytes(archive.read(entry))
    classes = RUN / "classes"
    classes.mkdir(exist_ok=True)
    command("compile-helper",[JAVA_HOME/"bin/javac.exe","-encoding","UTF-8","-cp",str(libs/"*"),"-d",classes,SCRIPT/"CurrentPerfHarness.java"])
    helper = start("helper",[JAVA,"-Xms64m","-Xmx256m","-Dfile.encoding=UTF-8",
        "-Dlogback.configurationFile="+str(PROJECT/"deploy/canal/e2e-logback.xml"),"-cp",str(classes)+";"+str(libs/"*"),"CurrentPerfHarness",RUN])
    wait_port(19080, helper)
    save("dependencies.json", control("/environment"))
    save("prepare.json", control("/prepare"))
    hashes = []
    for path in [JAR,*sorted((PROJECT/"src/main").rglob("*")),*sorted((PROJECT/"deploy/openresty").rglob("*")),*sorted(SCRIPT.glob("*"))]:
        if path.is_file():
            hashes.append({"path": str(path.relative_to(PROJECT)),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
    save("environment.json",{"time":now(),"logicalProcessors":os.cpu_count(),"memoryBytes":memory().total,
        "javaVersion":sp.check_output([str(JAVA),"-version"],stderr=sp.STDOUT).decode(),
        "k6Version":sp.check_output([str(K6),"version"]).decode(),"redisDatabase":14,
        "ports":{"edge":18084,"app":18085,"worker":18086,"broker":19111,"brokerHA":19112},
        "jvmHeapMiB":512,"sourceHashes":hashes,"topology":"Windows co-located load/apps/MySQL/Canal/Broker; VMware Redis; dedicated TCP fault gates"})
    start_app(18085,False)
    start_app(18086,True)
    prefix = RUN/"openresty"
    (prefix/"conf/lua").mkdir(parents=True,exist_ok=True)
    (prefix/"logs").mkdir(exist_ok=True)
    for path in (PROJECT/"deploy/openresty/lua").glob("*.lua"):
        (prefix/"conf/lua"/path.name).write_bytes(path.read_bytes())
    conf = (PROJECT/"deploy/openresty/nginx.conf").read_text(encoding="utf-8")
    conf = conf.replace("listen 8080;","listen 18084;").replace("127.0.0.1:8081","127.0.0.1:18085")
    conf = conf.replace("include mime.types;","include "+(NGINX.parent/"conf/mime.types").as_posix()+";")
    conf = conf.replace('$prefix/lualib/?.lua',NGINX.parent.as_posix()+"/lualib/?.lua")
    (prefix/"conf/nginx.conf").write_text(conf,encoding="utf-8")
    yaml = (PROJECT/"src/main/resources/application.yaml").read_text(encoding="utf-8")
    redis = yaml.split("  redis:",1)[1].split("  jackson:",1)[0]
    password = re.search(r"(?m)^    password: (.+)$",redis).group(1).strip()
    env = os.environ.copy()
    env.update(HMDP_REDIS_HOST="127.0.0.1",HMDP_REDIS_PORT="16379",HMDP_REDIS_DB="14",
        HMDP_REDIS_PASSWORD=password,HMDP_JAVA_HOST="127.0.0.1",HMDP_JAVA_PORT="18085",
        HMDP_CACHE_INTERNAL_KEY=(PROJECT/".local/cache-sync/internal-cache-key.txt").read_text().strip())
    command("nginx-config",[NGINX,"-t","-p",prefix.as_posix()+"/","-c","conf/nginx.conf"],env=env)
    wait_port(18084,start("openresty",[NGINX,"-p",prefix.as_posix()+"/","-c","conf/nginx.conf"],env=env,cwd=prefix))
    for name in ["namesrv","canal"]:
        pid = int((PROJECT/".local/cache-sync"/(name+".pid")).read_text().strip())
        STATE["processes"][name]={"pid":pid,"created":process_created(pid)}
    save("state.json",STATE)
    warm()
    smoke=load("smoke-shop","shop",10,EDGE,"5s")
    if smoke["metrics"]["content_valid"]["values"]["rate"]!=1:
        raise RuntimeError("Shop smoke invalid")
    voucher=control("/voucher?stock=20")
    result=load("smoke-order","order",2,APP,"5s",voucher["id"])
    accepted=int(result["metrics"]["business_accepted"]["values"]["count"])
    if accepted<9:raise RuntimeError("Order smoke did not accept requests")
    wait_orders(voucher["id"],accepted,"smoke-order")
    save("cache-baseline.json",control("/cache-stats"))
    control("/pause-observer")
    event("setup-complete")


def warm():
    fixture=json.loads((RUN/"fixture.json").read_text())
    for ident in fixture["hotShops"]+fixture["otherShops"]:
        if not get(EDGE+"/api/shop/"+str(ident))["success"]:
            raise RuntimeError("Warm response failed")


def load(name,mode,rate,base,duration="30s",voucher="",extra=None):
    if memory().available<1024**3:raise RuntimeError("Available memory below 1 GiB")
    event(name)
    envs={"FIXTURE":str(RUN/"fixture.json"),"MODE":mode,"RATE":str(rate),"BASE":base,
        "DURATION":duration,"SUMMARY":str(RUN/(name+".json")),"VOUCHER_ID":str(voucher),
        "EDGE":str(base==EDGE).lower(),**(extra or {})}
    args=[K6,"run","--no-color","--quiet","--log-format=json"]
    for key,value in envs.items():args += ["-e",key+"="+str(value)]
    args += [SCRIPT/"load-current.js"]
    if mode in ["order","duplicate"]:
        save(name+"-before-snapshot.json",control("/snapshot?id="+str(voucher)))
        save(name+"-clock-before.json",{**control("/clock"),"capturedHostEpochMs":time.time()*1000})
    start_epoch=time.time()
    proc=start("load-"+name,args)
    deadline=time.monotonic()+300
    while proc.poll() is None:
        # 开始前有余量不保证整轮都有余量；后续补测持续检查，低于预算立即保留中断现场。
        if memory().available<1024**3:
            proc.terminate()
            proc.wait(timeout=15)
            save(name+"-aborted.json",{"time":now(),"reason":"memory below 1 GiB during round"})
            raise RuntimeError("Available memory below 1 GiB during "+name)
        if time.monotonic()>deadline:
            proc.terminate()
            proc.wait(timeout=15)
            raise TimeoutError("Load did not finish: "+name)
        time.sleep(.25)
    code=proc.returncode
    STATE["processes"].pop("load-"+name,None)
    save("state.json",STATE)
    if mode in ["order","duplicate"]:save(name+"-clock-after.json",{**control("/clock"),"capturedHostEpochMs":time.time()*1000})
    save(name+"-execution.json",{"startEpoch":start_epoch,"endEpoch":time.time(),"exitCode":code})
    result=json.loads((RUN/(name+".json")).read_text())
    event(name+"-complete",requests=result["metrics"]["http_reqs"]["values"]["count"],
        p95Ms=result["metrics"]["http_req_duration"]["values"]["p(95)"],exitCode=code)
    return result


def wait_orders(ident,expected,name,failed=False,timeout=180):
    start=time.monotonic()
    last={}
    while time.monotonic()-start<timeout:
        last=control("/snapshot?id="+str(ident))
        save(name+"-snapshot.json",last)
        # Pending=0 alone is insufficient: require the group to have read the stream tail.
        groups=last.get("groups",[])
        read_tail=False
        for group in groups:
            if isinstance(group,list):
                group=dict(zip(group[::2],group[1::2]))
            if isinstance(group,dict) and group.get("name")=="g1":
                read_tail=group.get("last-delivered-id")==last.get("lastStreamId")
        finished=last.get("terminalObservation",{}).get("n",0)
        if finished==expected and last.get("pending")==0 and last.get("dlqLength")==0 and read_tail:
            valid=(last.get("orders")==0 if failed else last.get("orders")==expected and last.get("distinctUsers")==expected)
            save(name+"-drain.json",{"seconds":time.monotonic()-start,"expected":expected,"finished":finished,"valid":valid})
            if not valid:raise RuntimeError("Order correctness failure: "+name)
            return last
        time.sleep(.5)
    save(name+"-drain.json",{"timedOut":True,"seconds":time.monotonic()-start,"expected":expected,"last":last})
    raise TimeoutError("Orders did not drain: "+name)


def caches():
    control("/pause-observer")
    load("warm-java","shop",300,APP,"45s")
    load("warm-edge","shop",300,EDGE,"10s")
    for kind,base in [("edge",EDGE),("java",APP)]:
        for rate in [100,300,600]:
            for repeat in range(1,4):
                load(f"cache-hot-{kind}-{rate}-r{repeat}","shop",rate,base)
    for kind,base in [("edge",EDGE),("java",APP)]:
        for repeat in range(1,4):load(f"cache-normal-{kind}-300-r{repeat}","normal",300,base)
    for repeat in range(1,4):load(f"cache-mixed-300-r{repeat}","mixed",300,EDGE)
    for repeat in range(1,4):load(f"cache-cold-r{repeat}","cold",0,EDGE,extra={"COLD_OFFSET":(repeat-1)*100,"ITERATIONS":100,"VUS":10})
    fixture=json.loads((RUN/"fixture.json").read_text())
    for port in [18085,18084]:
        for repeat in range(1,4):
            name=f"cache-burst-{port}-r{repeat}"
            event(name)
            save(name+".json",control(f"/burst?id={fixture['otherShops'][1]}&port={port}"))
    for repeat in range(1,4):
        control("/logical?id="+str(fixture["hotShops"][1]))
        time.sleep(5.2)
        load(f"cache-logical-r{repeat}","logical",100,EDGE,"10s",extra={"SHOP_ID":fixture["hotShops"][1]})
        save(f"cache-logical-r{repeat}-stats.json",control("/cache-stats"))
    save("cache-final.json",control("/cache-stats"))
    event("cache-complete")


def orders():
    def done(name):
        summary=RUN/(name+".json")
        drained=RUN/(name+"-drain.json")
        if not summary.exists():return False
        if drained.exists() and json.loads(drained.read_text()).get("valid") is True:return True
        raise RuntimeError("Existing partial round requires diagnosis; will not overwrite "+name)

    warm_name="warm-orders"
    if done(warm_name):
        index=1
        while (RUN/f"warm-orders-resume-{index}.json").exists():index+=1
        warm_name=f"warm-orders-resume-{index}"
    voucher=control("/voucher?stock=1000")
    result=load(warm_name,"order",10,APP,"20s",voucher["id"])
    wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),warm_name)
    for rate in [10,30,60]:
        for repeat in range(1,4):
            name=f"orders-{rate}-r{repeat}"
            if done(name):
                event("resume-skip-completed",round=name)
                continue
            voucher=control("/voucher?stock=3000")
            result=load(name,"order",rate,APP,voucher=voucher["id"])
            wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),name)
    if not done("orders-stock-competition"):
        voucher=control("/voucher?stock=300")
        result=load("orders-stock-competition","order",200,APP,"7.5s",voucher["id"])
        accepted=int(result["metrics"]["business_accepted"]["values"]["count"])
        if accepted!=300:raise RuntimeError("Stock competition accepted "+str(accepted))
        wait_orders(voucher["id"],accepted,"orders-stock-competition")
    if not done("orders-duplicate-user"):
        voucher=control("/voucher?stock=10")
        result=load("orders-duplicate-user","duplicate",50,APP,"4s",voucher["id"])
        accepted=int(result["metrics"]["business_accepted"]["values"]["count"])
        if accepted!=1:raise RuntimeError("Duplicate purchase accepted "+str(accepted))
        wait_orders(voucher["id"],1,"orders-duplicate-user")
    if not (RUN/"orders-replay-comparison.json").exists():
        result=json.loads((RUN/"orders-duplicate-user.json").read_text())
        ident=result["test"]["voucherId"]
        before=control("/snapshot?id="+ident)
        save("orders-replay-enqueue.json",control("/replay?id="+ident+"&count=100"))
        time.sleep(1)
        after=wait_orders(ident,1,"orders-replay")
        save("orders-replay-comparison.json",{"before":before,"after":after})
    control("/pause-observer")
    event("orders-complete")


def cold_control():
    """追加对照：解释不同冷ID在无队列、4路源重建预算下的拒绝边界。"""
    control("/pause-observer")
    fixture=json.loads((RUN/"fixture.json").read_text())
    for vus in [1,4]:
        for repeat in range(1,4):
            for ident in fixture["coldShops"][:100]:control("/clear?id="+str(ident))
            load(f"cache-cold-control-vus{vus}-r{repeat}","cold",0,EDGE,extra={"COLD_OFFSET":0,"ITERATIONS":100,"VUS":vus})
    event("cold-control-complete")


def sync():
    control("/pause-observer")
    for background in [False,True]:
        background_process=None
        if background:
            args=[K6,"run","--quiet","--no-color","-e","FIXTURE="+str(RUN/"fixture.json"),"-e","MODE=mixed",
                "-e","BASE="+EDGE,"-e","EDGE=true","-e","RATE=300","-e","DURATION=180s",
                "-e","SUMMARY="+str(RUN/"cdc-background-load.json"),SCRIPT/"load-current.js"]
            background_process=start("cdc-background",args)
        try:
            for kind in ["HOT","NORMAL"]:
                name="cdc-"+kind.lower()+("-load" if background else "-idle")
                event(name)
                control("/sync-start?count=30&kind="+kind)
                deadline=time.monotonic()+600
                while time.monotonic()<deadline:
                    value=control("/sync-result")
                    if value["state"]!="running":
                        save(name+".json",value)
                        if value["state"]!="complete":raise RuntimeError("CDC sampler failed")
                        break
                    time.sleep(.5)
                else:raise TimeoutError("CDC sampler timed out")
        finally:
            if background_process:
                background_process.wait(timeout=200)
                STATE["processes"].pop("cdc-background",None)
    event("sync-complete")


def availability():
    fixture=json.loads((RUN/"fixture.json").read_text())
    voucher=control("/voucher?stock=100")
    event("fault-mysql")
    control("/fault?target=mysql&healthy=false")
    try:
        result=load("fault-mysql-ingress","order",10,APP,"5s",voucher["id"])
        save("fault-mysql-during.json",control("/snapshot?id="+voucher["id"]))
        time.sleep(10)
    finally:control("/fault?target=mysql&healthy=true")
    wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),"fault-mysql-recovery")
    voucher=control("/voucher?stock=100")
    event("fault-consumer-crash")
    control("/fault?target=mysql&healthy=false")
    result=load("fault-crash-ingress","order",5,APP,"4s",voucher["id"])
    save("fault-crash-pending.json",control("/snapshot?id="+voucher["id"]))
    stop("app-18086")
    control("/fault?target=mysql&healthy=true")
    started=time.time()
    start_app(18086,True)
    save("fault-crash-restart.json",{"startedEpoch":started,"readyEpoch":time.time()})
    wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),"fault-crash-recovery")
    control("/pause-observer")
    ident=fixture["hotShops"][2]
    control("/read?id="+str(ident))
    control("/fault?target=redis&healthy=false")
    try:
        result=load("fault-redis-hot-read","shop",50,EDGE,"8s")
    finally:control("/fault?target=redis&healthy=true")
    time.sleep(6)
    save("fault-redis-restored.json",control("/read?id="+str(ident)))
    event("fault-permanent-order")
    voucher=control("/voucher?stock=10&dbStock=0")
    result=load("fault-permanent-ingress","order",2,APP,"2s",voucher["id"])
    accepted=int(result["metrics"]["business_accepted"]["values"]["count"])
    final=wait_orders(voucher["id"],accepted,"fault-permanent-compensated",failed=True,timeout=240)
    if final["redisStock"]!="10" or final["purchasers"]!=0 or final["reservations"]!=0:
        raise RuntimeError("Compensation invariant failed")
    save("availability-final.json",control("/cache-stats"))
    control("/pause-observer")
    # 只停止/重启本轮登记的进程，沿用持久化存储和采集位点；不重置真实队列。
    for service in ["canal","broker"]:
        ident=fixture["hotShops"][0]
        control("/read?id="+str(ident))
        event("fault-"+service+"-restart")
        stop(service)
        update=control("/update?id="+str(ident)+"&version="+service+"-restart")
        save("fault-"+service+"-update.json",update)
        time.sleep(10)
        restarted=time.time()
        if service=="broker":
            arguments=json.loads((RUN/"broker-arguments.json").read_text())
            wait_port(19111,start("broker",arguments))
        else:
            command("restart-canal",["powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",PROJECT/"deploy/canal/start-canal.ps1"])
            pid=int((PROJECT/".local/cache-sync/canal.pid").read_text().strip())
            STATE["processes"]["canal"]={"pid":pid,"created":process_created(pid)}
            save("state.json",STATE)
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            reads=control("/read?id="+str(ident))
            if all(value.get("name")==update["name"] for value in reads.values()):
                save("fault-"+service+"-recovery.json",{"restartedEpoch":restarted,"recoveredEpoch":time.time(),"reads":reads})
                break
            time.sleep(.5)
        else:raise TimeoutError(service+" restart did not recover CDC")
    event("fault-edge-notification")
    ident=fixture["hotShops"][0]
    control("/read?id="+str(ident))
    before=control("/health")
    control("/fault?target=edge&healthy=false")
    update=control("/update?id="+str(ident)+"&version=edge-retry")
    try:
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            if control("/health")["edgeFailure"]>before["edgeFailure"]:break
            time.sleep(.2)
        else:raise TimeoutError("Expected real CDC notification failure was not observed")
        save("fault-edge-retry-during.json",control("/health"))
    finally:control("/fault?target=edge&healthy=true")
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        health=control("/health")
        if health["edgeSuccess"]>before["edgeSuccess"]:
            reads=control("/read?id="+str(ident))
            if all(value.get("name")==update["name"] for value in reads.values()):
                save("fault-edge-retry-recovery.json",{"health":health,"update":update,"reads":reads,"recoveredHostEpochMs":time.time()*1000})
                break
        time.sleep(.5)
    else:raise TimeoutError("Broker notification retry did not converge")
    event("availability-complete")


def cleanup():
    event("cleanup")
    for name in ["app-18085","app-18086"]:stop(name)
    command("nginx-quit",[NGINX,"-p",(RUN/"openresty").as_posix()+"/","-c","conf/nginx.conf","-s","quit"])
    time.sleep(1)
    save("cleanup.json",control("/cleanup"))
    for name in ["helper","openresty","broker","canal","namesrv"]:stop(name)
    (RUN/"resource-stop.flag").write_text(now())
    event("complete")


def memory_control():
    """对原始测量中触及内存阈值的档位补齐三轮，原数据永不覆盖。"""
    if (RUN/"fixture.json").exists():raise RuntimeError("Use a fresh control run ID")
    event("memory-control-dependencies")
    broker_home=Path(r"D:\rocketmq-all-5.3.3-bin-release")
    runtime=PROJECT/".local/cache-sync"
    args=[JAVA,"-Xms128m","-Xmx512m","-XX:MaxDirectMemorySize=256m"]
    args += ["--add-opens=java.base/"+x+"=ALL-UNNAMED" for x in ["java.lang","java.nio","sun.nio.ch","java.util"]]
    args += ["-Duser.home="+str(runtime),"-Drocketmq.home.dir="+str(broker_home),"-cp",
        str(broker_home/"conf")+";"+str(broker_home/"lib/*"),"org.apache.rocketmq.namesrv.NamesrvStartup","-c",runtime/"namesrv.conf"]
    wait_port(9876,start("namesrv",args))
    (runtime/"namesrv.pid").write_text(str(STATE["processes"]["namesrv"]["pid"]))
    command("control-start-canal",["powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",PROJECT/"deploy/canal/start-canal.ps1"])
    pid=int((runtime/"canal.pid").read_text().strip())
    STATE["processes"]["canal"]={"pid":pid,"created":process_created(pid)}
    save("state.json",STATE)
    setup()
    load("warm-java","shop",300,APP,"45s")
    load("warm-edge","shop",300,EDGE,"10s")
    for repeat in range(1,4):load(f"control-hot-edge-600-r{repeat}","shop",600,EDGE)
    for repeat in range(1,4):load(f"control-hot-java-100-r{repeat}","shop",100,APP)
    voucher=control("/voucher?stock=1000")
    result=load("warm-orders","order",10,APP,"20s",voucher["id"])
    wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),"warm-orders")
    for repeat in range(1,4):
        voucher=control("/voucher?stock=3000")
        name=f"control-orders-10-r{repeat}"
        result=load(name,"order",10,APP,voucher=voucher["id"])
        wait_orders(voucher["id"],int(result["metrics"]["business_accepted"]["values"]["count"]),name)
    control("/pause-observer")
    event("memory-control-measurements-complete")
    cleanup()


def main():
    global RUN, STATE
    parser=argparse.ArgumentParser()
    parser.add_argument("--run-id",default="run-20261018-v4")
    parser.add_argument("--phase",choices=["setup","cache","orders","cold-control","sync","availability","cleanup","remaining","all","memory-control"],default="all")
    args=parser.parse_args()
    if not re.fullmatch(r"run-[a-zA-Z0-9-]+",args.run_id):raise ValueError("Invalid run ID")
    RUN=PROJECT/".local/performance"/args.run_id
    RUN.mkdir(exist_ok=True,parents=True)
    STATE=json.loads((RUN/"state.json").read_text()) if (RUN/"state.json").exists() else {"processes":{}}
    monitor_thread=threading.Thread(target=monitor,daemon=True)
    monitor_thread.start()
    try:
        if args.phase=="memory-control":
            memory_control()
            return
        for phase,action in [("setup",setup),("cache",caches),("orders",orders),("cold-control",cold_control),("sync",sync),("availability",availability),("cleanup",cleanup)]:
            if args.phase in [phase,"all"] or (args.phase=="remaining" and phase in ["cold-control","sync","availability","cleanup"]):action()
    except BaseException as error:
        save("failure-"+args.phase+".json",{"time":now(),"error":repr(error)})
        event("failed",phase=args.phase,error=repr(error))
        for target in ["mysql","redis","edge"]:
            try:control("/fault?target="+target+"&healthy=true",timeout=5)
            except Exception:pass
        raise
    finally:
        MONITOR_STOP.set()
        monitor_thread.join(timeout=3)


if __name__=="__main__":
    main()
