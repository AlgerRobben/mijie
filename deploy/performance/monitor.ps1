param([Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference='Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PerfSystemStats {
 [StructLayout(LayoutKind.Sequential)] public struct Memory {
  public uint Length, Load; public ulong TotalPhys, AvailPhys, TotalPage, AvailPage, TotalVirtual, AvailVirtual, Extended;
 }
 [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref Memory m);
 [DllImport("kernel32.dll")] static extern bool GetSystemTimes(out long idle,out long kernel,out long user);
 static long pi,pk,pu;
 public static double[] Read() {
  var m=new Memory();m.Length=(uint)Marshal.SizeOf(typeof(Memory));GlobalMemoryStatusEx(ref m);
  long i,k,u;GetSystemTimes(out i,out k,out u);long total=k-pk+u-pu;double cpu=total>0?100.0*(total-(i-pi))/total:0;pi=i;pk=k;pu=u;
  return new double[]{m.TotalPhys,m.AvailPhys,cpu};
 }
}
'@
$previous=@{}
while(Test-Path -LiteralPath (Join-Path $RunDirectory 'monitor.alive')) {
    $system=[PerfSystemStats]::Read()
    $items=@()
    try {
        $pids=Get-Content -LiteralPath (Join-Path $RunDirectory 'pids.json') -Raw | ConvertFrom-Json
        foreach($property in $pids.PSObject.Properties) {
            $process=Get-Process -Id ([int]$property.Value) -ErrorAction SilentlyContinue
            if(!$process){continue}
            $total=$null
            try{$total=$process.TotalProcessorTime.TotalSeconds}catch{}
            $cpu=$null
            if($null-ne $total -and $previous.ContainsKey($process.Id)) {
                $elapsed=([DateTime]::UtcNow-$previous[$process.Id].Time).TotalSeconds
                if($elapsed-gt 0){$cpu=100*($total-$previous[$process.Id].Cpu)/$elapsed}
            }
            $previous[$process.Id]=@{Time=[DateTime]::UtcNow;Cpu=$total}
            $items+=@{name=$property.Name;pid=$process.Id;cpuPercentOneCore=$cpu;workingSetBytes=$process.WorkingSet64;threads=$process.Threads.Count}
        }
        foreach($process in @(Get-Process -Name k6 -ErrorAction SilentlyContinue)) {
            $cpu=$null
            if($previous.ContainsKey($process.Id)){$elapsed=([DateTime]::UtcNow-$previous[$process.Id].Time).TotalSeconds;if($elapsed-gt 0){$cpu=100*($process.TotalProcessorTime.TotalSeconds-$previous[$process.Id].Cpu)/$elapsed}}
            $previous[$process.Id]=@{Time=[DateTime]::UtcNow;Cpu=$process.TotalProcessorTime.TotalSeconds}
            $items+=@{name='k6';pid=$process.Id;cpuPercentOneCore=$cpu;workingSetBytes=$process.WorkingSet64;threads=$process.Threads.Count}
        }
        $phase='unknown';$phaseFile=Join-Path $RunDirectory 'phase.txt';if(Test-Path -LiteralPath $phaseFile){$phase=(Get-Content -LiteralPath $phaseFile -Raw).Trim()}
        @{time=[DateTime]::UtcNow.ToString('o');phase=$phase;systemCpuPercent=$system[2];availableMemoryBytes=$system[1];totalMemoryBytes=$system[0];processes=$items} | ConvertTo-Json -Depth 6 -Compress | Add-Content -LiteralPath (Join-Path $RunDirectory 'resources.jsonl') -Encoding utf8
    }catch{ $_.Exception.Message | Add-Content -LiteralPath (Join-Path $RunDirectory 'monitor-errors.log') }
    Start-Sleep -Seconds 1
}
