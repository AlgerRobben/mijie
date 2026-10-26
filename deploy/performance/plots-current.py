"""Publication-style plots from measured current-suite summaries only."""
from pathlib import Path
import json
import os
import re
import statistics
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/".local/performance/plotting"))
os.environ["MPLCONFIGDIR"]=str(ROOT/".local/performance/mplconfig")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

out=Path(sys.argv[1]).resolve()
data=json.loads((out/"summary.json").read_text(encoding="utf-8"))
font=Path("C:/Windows/Fonts/msyh.ttc")
if font.exists():
    font_manager.fontManager.addfont(str(font))
    plt.rcParams["font.family"]=font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams.update({"axes.unicode_minus":False,"font.size":10,"axes.spines.top":False,"axes.spines.right":False})
rounds=data["rounds"]

def measured(pattern,field):
    return [r[field] for r in rounds if re.fullmatch(pattern,r["name"]) and r.get(field) is not None]

fig,axes=plt.subplots(1,2,figsize=(11,4.7),layout="constrained")
for kind,label,color in [("edge","HOT 边缘","#157A8C"),("java","HOT Java","#B56336")]:
    xs=[];ys=[];low=[];high=[]
    for rate in [100,300,600]:
        values=measured(fr"cache-hot-{kind}-{rate}-r\d+","p95Ms")
        if values:
            mid=statistics.median(values);xs.append(rate);ys.append(mid);low.append(mid-min(values));high.append(max(values)-mid)
    axes[0].errorbar(xs,ys,yerr=[low,high],fmt="o-",capsize=5,label=label,color=color)
axes[0].set(title="热点查询",xlabel="目标请求速率（RPS）",ylabel="HTTP p95（ms）",xticks=[100,300,600],ylim=(0,None))
axes[0].legend()
for i,(pattern,label) in enumerate([(r"cache-normal-edge-300-r\d+","普通·边缘"),(r"cache-normal-java-300-r\d+","普通·Java"),(r"cache-mixed-300-r\d+","混合·边缘")]):
    values=measured(pattern,"p95Ms")
    if values:
        mid=statistics.median(values)
        axes[1].bar(i,mid,color=["#157A8C","#B56336","#6875A4"][i],width=.5)
        axes[1].errorbar(i,mid,yerr=[[mid-min(values)],[max(values)-mid]],fmt="none",capsize=5,color="#333333")
        axes[1].text(i,mid+.3,f"{mid:.2f}",ha="center")
axes[1].set(title="普通与混合查询：300 RPS",ylabel="HTTP p95（ms）",xticks=[0,1,2],xticklabels=["普通·边缘","普通·Java","混合·边缘"],ylim=(0,None))
for ax in axes:ax.grid(axis="y",alpha=.2)
fig.suptitle("缓存原始轮：p95 中位数与范围；低内存补测及 dropped 见报告")
fig.savefig(out/"缓存查询延迟.png",dpi=170);plt.close(fig)

fig,axes=plt.subplots(2,1,figsize=(8.5,7.2),layout="constrained")
for axis,field,label in [(axes[0],"p95Ms","HTTP 受理接口 p95（ms）"),(axes[1],"clientToDatabase","请求开始至 DB 可见 p95（秒）")]:
    xs=[];ys=[];low=[];high=[]
    for rate in [10,30,60]:
        rows=[r for r in rounds if re.fullmatch(fr"orders-{rate}-r\d+",r["name"])]
        values=[r[field]["p95"]/1000 for r in rows if r.get(field,{}).get("n",0)] if field=="clientToDatabase" else [r[field] for r in rows]
        if values:
            mid=statistics.median(values);xs.append(rate);ys.append(mid);low.append(mid-min(values));high.append(max(values)-mid)
    axis.errorbar(xs,ys,yerr=[low,high],fmt="o-",capsize=5,color="#157A8C")
    for x,y in zip(xs,ys):axis.annotate(f"{y:.2f}",(x,y),xytext=(0,10),textcoords="offset points",ha="center")
    axis.set(xlabel="目标秒杀受理速率（RPS）",ylabel=label,xticks=[10,30,60],ylim=(0,None));axis.grid(axis="y",alpha=.2)
axes[1].axhline(5,color="#AA3F3F",linestyle="--",label="预设终态目标：5 秒");axes[1].legend()
fig.suptitle("异步订单原始轮：p95 中位数与范围，低内存补测见报告\n接口与最终落库分开测量，终态含 200ms 轮询")
fig.savefig(out/"订单受理与终态延迟.png",dpi=170);plt.close(fig)

fig,ax=plt.subplots(figsize=(9,4.7),layout="constrained")
for i,key in enumerate(["cdc-hot-idle","cdc-normal-idle","cdc-hot-load","cdc-normal-load"]):
    item=data["cdc"].get(key)
    if item and item["delayMs"].get("n"):
        p95=item["delayMs"]["p95"]
        ax.bar(i,p95,color="#157A8C" if "hot" in key else "#6875A4",width=.55)
        ax.text(i,p95+max(5,p95*.03),f"{p95:.0f}ms\n{item['successful']}/{item['count']} 成功",ha="center")
ax.set(xticks=range(4),xticklabels=["HOT 空载","NORMAL 空载","HOT 背景读","NORMAL 背景读"],ylabel="SQL 提交返回至全部入口新值 p95（ms）",title="真实 Canal→RocketMQ→缓存同步：每组 30 样本",ylim=(0,None))
maximum=max((v["delayMs"].get("p95",0) for v in data["cdc"].values()),default=1)
ax.set_ylim(0,max(1,maximum)*1.28)
ax.set_title("真实 Canal→RocketMQ→缓存同步：每组 30 样本",pad=16)
ax.grid(axis="y",alpha=.2)
fig.savefig(out/"缓存同步收敛延迟.png",dpi=170);plt.close(fig)
print("Created 3 measured-result figures")
