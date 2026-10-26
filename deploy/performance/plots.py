from pathlib import Path
import json, os, re, statistics, sys
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'.local/performance/plotting'))
os.environ['MPLCONFIGDIR'] = str(ROOT/'.local/performance/mplconfig')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
OUT = Path(sys.argv[1]).resolve()
data = json.loads((OUT/'summary.json').read_text(encoding='utf-8'))
rows = data['rounds']
font = Path('C:/Windows/Fonts/msyh.ttc')
if font.exists():
    font_manager.fontManager.addfont(str(font))
    plt.rcParams['font.family'] = font_manager.FontProperties(fname=str(font)).get_name()
plt.rcParams.update({'axes.unicode_minus':False,'font.size':11,'axes.spines.top':False,'axes.spines.right':False})

def series(pattern, field):
    x=[]; med=[]; low=[]; high=[]
    for rate in [100,300,600] if field=='p95Ms' else [10,30,60]:
        values=[r[field] for r in rows if re.fullmatch(pattern.format(rate=rate),r['name']) and r.get(field) is not None]
        if values:
            middle=statistics.median(values);x.append(rate);med.append(middle);low.append(middle-min(values));high.append(max(values)-middle)
    return x,med,[low,high]

fig,ax=plt.subplots(figsize=(9,4.6),layout='constrained')
for pattern,label,color in [(r'cache-edge-{rate}-r\d+','边缘热点','#147D92'),(r'cache-java-{rate}-r\d+','Java热点（原始组）','#E17438')]:
    x,y,error=series(pattern,'p95Ms');ax.errorbar(x,y,yerr=error,fmt='o-',capsize=5,label=label,color=color,linewidth=2)
warm=[r['p95Ms'] for r in rows if re.fullmatch(r'cache-java-warm-clean-100-r\d+',r['name'])]
if warm: ax.scatter([100],[statistics.median(warm)],marker='D',s=65,label='Java 100 RPS：充分预热后',color='#5963B3')
ax.set(xlabel='目标请求速率（RPS）',ylabel='HTTP p95（ms）',title='缓存查询：三轮 p95 中位数与最小/最大范围',xticks=[100,300,600],ylim=(0,None))
ax.grid(axis='y',alpha=.2);ax.legend();fig.savefig(OUT/'cache-p95.png',dpi=170);plt.close(fig)

fig,ax=plt.subplots(figsize=(9,4.6),layout='constrained')
x,y,error=series(r'order-{rate}-r\d+','finalP95Ms');y=[v/1000 for v in y];error=[[v/1000 for v in group] for group in error]
ax.errorbar(x,y,yerr=error,fmt='o-',capsize=6,linewidth=2,color='#147D92',label='受理至数据库终态可见')
ax.axhline(5,color='#B24242',linestyle='--',label='预设 p95 目标：5秒')
for rate,value in zip(x,y): ax.annotate(f'{value:.2f}s',(rate,value),xytext=(0,12),textcoords='offset points',ha='center')
ax.set(xlabel='秒杀受理目标速率（RPS）',ylabel='终态 p95（秒）',title='异步订单：接口返回很快，60 RPS时终态出现积压',xticks=[10,30,60],ylim=(0,12))
ax.text(.02,.95,'三轮中位数/范围；包含约200ms的提交观测轮询',transform=ax.transAxes,va='top',fontsize=10)
ax.grid(axis='y',alpha=.2);ax.legend(loc='center left');fig.savefig(OUT/'order-p95.png',dpi=170);plt.close(fig)

(OUT/'plot-tool.json').write_text(json.dumps({'matplotlib':matplotlib.__version__,'numpy':__import__('numpy').__version__},indent=2),encoding='utf-8')
print('Generated cache-p95.png and order-p95.png')
