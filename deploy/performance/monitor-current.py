"""Read-only supplementary sampler for VM, MySQL and Nginx worker resources."""
import ctypes as c
from ctypes import wintypes as w
import json
from pathlib import Path
import sys
import time

run=Path(sys.argv[1]).resolve()
kernel=c.WinDLL("kernel32",use_last_error=True)
psapi=c.WinDLL("psapi",use_last_error=True)
kernel.CreateToolhelp32Snapshot.restype=w.HANDLE
kernel.OpenProcess.restype=w.HANDLE
kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD]
kernel.CloseHandle.argtypes=[w.HANDLE]
kernel.GetProcessTimes.argtypes=[w.HANDLE]+[c.POINTER(w.FILETIME)]*4
psapi.GetProcessMemoryInfo.argtypes=[w.HANDLE,c.c_void_p,w.DWORD]

class Entry(c.Structure):
    _fields_=[("size",w.DWORD),("usage",w.DWORD),("pid",w.DWORD),("heap",c.c_size_t),
        ("module",w.DWORD),("threads",w.DWORD),("parent",w.DWORD),("priority",w.LONG),
        ("flags",w.DWORD),("exe",w.WCHAR*260)]

class Memory(c.Structure):
    _fields_=[("size",w.DWORD),("faults",w.DWORD)]+[(x,c.c_size_t) for x in
        ["peakWorking","working","peakPaged","paged","peakNonPaged","nonPaged","pagefile","peakPagefile"]]

kernel.Process32FirstW.argtypes=[w.HANDLE,c.POINTER(Entry)]
kernel.Process32NextW.argtypes=[w.HANDLE,c.POINTER(Entry)]
deadline=time.monotonic()+7200
with (run/"infrastructure-resources.jsonl").open("a",encoding="utf-8") as out:
    while time.monotonic()<deadline and not (run/"resource-stop.flag").exists():
        processes=[]
        snapshot=kernel.CreateToolhelp32Snapshot(2,0)
        entry=Entry();entry.size=c.sizeof(entry)
        ok=kernel.Process32FirstW(snapshot,c.byref(entry))
        while ok:
            if entry.exe.lower() in {"mysqld.exe","vmware-vmx.exe","nginx.exe"}:
                row={"pid":entry.pid,"parentPid":entry.parent,"name":entry.exe}
                handle=kernel.OpenProcess(0x410,False,entry.pid)
                if handle:
                    pm=Memory();pm.size=c.sizeof(pm)
                    if psapi.GetProcessMemoryInfo(handle,c.byref(pm),c.sizeof(pm)):
                        row["workingSetBytes"]=pm.working
                    times=[w.FILETIME() for _ in range(4)]
                    if kernel.GetProcessTimes(handle,*(c.byref(t) for t in times)):
                        row["cpuSeconds"]=sum((t.dwHighDateTime<<32)|t.dwLowDateTime for t in times[2:])/1e7
                    kernel.CloseHandle(handle)
                else:
                    row["unavailableWinError"]=c.get_last_error()
                processes.append(row)
            ok=kernel.Process32NextW(snapshot,c.byref(entry))
        kernel.CloseHandle(snapshot)
        out.write(json.dumps({"epoch":time.time(),"processes":processes})+"\n");out.flush()
        time.sleep(1)
