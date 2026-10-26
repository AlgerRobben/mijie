"""Read-only Redis durability and host metadata snapshot; never prints credentials."""
import json
from pathlib import Path
import re
import socket
import sys
import time
import winreg

project=Path(__file__).resolve().parents[2]
run=Path(sys.argv[1]).resolve()
yaml=(project/"src/main/resources/application.yaml").read_text(encoding="utf-8")
section=yaml.split("  redis:",1)[1].split("  jackson:",1)[0]
def setting(name):return re.search(r"(?m)^    "+name+r": (.+)$",section).group(1).strip()

with socket.create_connection((setting("host"),int(setting("port"))),timeout=5) as sock:
    stream=sock.makefile("rb")
    def read():
        line=stream.readline()
        kind,value=line[:1],line[1:-2]
        if kind==b"-":raise RuntimeError(value.decode())
        if kind in [b"+",b":"]:return value.decode()
        if kind==b"$":
            size=int(value)
            if size<0:return None
            data=stream.read(size);stream.read(2);return data.decode()
        if kind==b"*":return [read() for _ in range(int(value))]
        raise RuntimeError("Unexpected Redis protocol response")
    def command(*args):
        parts=[str(arg).encode() for arg in args]
        sock.sendall(b"*"+str(len(parts)).encode()+b"\r\n"+b"".join(b"$"+str(len(p)).encode()+b"\r\n"+p+b"\r\n" for p in parts))
        return read()
    command("AUTH",setting("password"))
    result={"capturedHostEpochMs":time.time()*1000,"redisConfig":{}}
    for key in ["appendonly","appendfsync","save","maxmemory","maxmemory-policy","maxclients","databases"]:
        response=command("CONFIG","GET",key)
        result["redisConfig"][key]=response[1] if len(response)==2 else response
    for section in ["cpu","persistence","stats"]:
        value=command("INFO",section)
        result["redisInfo"+section.title()]={line.split(":",1)[0]:line.split(":",1)[1] for line in value.splitlines() if ":" in line and not line.startswith("#")}

with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
    result["cpuName"]=winreg.QueryValueEx(key,"ProcessorNameString")[0].strip()
result["windowsVersion"]=list(sys.getwindowsversion())
path=run/(sys.argv[2] if len(sys.argv)>2 else "redis-config.json")
path.write_text(json.dumps(result,indent=2),encoding="utf-8")
print(json.dumps({"saved":str(path),"appendonly":result["redisConfig"]["appendonly"],"appendfsync":result["redisConfig"]["appendfsync"],"cpu":result["cpuName"]}))
