#!/usr/bin/env python3
"""Create README charts directly from the committed JMH JSON."""
import json
import math
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

root = Path(__file__).resolve().parent
rows = json.loads((root / 'results/macos-arm64-jdk25.json').read_text())
records = {(r['benchmark'].rsplit('.', 1)[-1], r['params']['dataset'], r['params']['compression']): r for r in rows}
assert len(records) == len(rows) == 20
out = root / 'results/charts'
out.mkdir(exist_ok=True)
colors = ['#2563eb', '#d97706', '#64748b']
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False,
                     'axes.spines.right': False, 'axes.spines.left': False, 'svg.hashsalt': 'webpbridge'})
for operation in ['encode', 'decode']:
    methods = [(operation+'Jni', 'webpbridge'), (operation+'Imageio', 'webp-imageio')]
    if operation == 'decode': methods.append(('decodeTwelveMonkeys', 'TwelveMonkeys'))
    for allocation in [False, True]:
        fig, axes = plt.subplots(2, 2, figsize=(12, 7.7))
        metric_name = 'Java heap allocation' if allocation else 'Time per operation'
        unit = 'KiB/op' if allocation else 'ms/op'
        fig.suptitle(f'{operation.capitalize()} · {metric_name}', x=.06, y=.98, ha='left', weight='bold', fontsize=20)
        fig.text(.06,.92,'Apple M1 · macOS ARM64 · JDK 25 · JMH 1.37 | Lower is better',color='#475569')
        fig.legend(handles=[Patch(color=colors[i],label=label) for i,(_,label) in enumerate(methods)],
                   loc='upper left',bbox_to_anchor=(.05,.91),ncol=3,frameon=False)
        for ax,(dataset,mode) in zip(axes.flat,[(d,m) for d in ['ui256','texture1024'] for m in ['lossless','lossy80']]):
            scores,errors=[],[]
            for method,_ in methods:
                row=records[method,dataset,mode]
                metric=row['secondaryMetrics']['gc.alloc.rate.norm'] if allocation else row['primaryMetric']
                assert metric['scoreUnit']==('B/op' if allocation else 'ms/op')
                scale=1024 if allocation else 1
                scores.append(metric['score']/scale);errors.append(metric['scoreError']/scale)
            assert all(math.isfinite(v) and v>=0 for v in scores+errors)
            largest=max(s+e for s,e in zip(scores,errors))
            ax.barh(range(len(methods)),scores,xerr=errors,color=colors[:len(methods)],height=.55,
                    error_kw={'ecolor':'#1e293b','capsize':4,'elinewidth':1.1})
            for i,(s,e) in enumerate(zip(scores,errors)):
                text=f'{s:,.1f}' if allocation else f'{s:.3f}'
                ax.text(s+e+largest*.025,i,text,va='center',fontsize=10,color='#0f172a')
            ax.set_yticks(range(len(methods)),[label for _,label in methods],fontsize=10)
            ax.invert_yaxis();ax.set_xlim(0,largest*1.30)
            ax.set_title(f'{dataset} · {mode}',loc='left',weight='bold',fontsize=12,pad=12)
            ax.set_xlabel(unit);ax.xaxis.grid(True,alpha=.18);ax.set_axisbelow(True)
            ax.tick_params(axis='both',length=0)
        note='Bars: means; whiskers: 99.9% CI half-width. Linear axes start at zero; ranges differ by panel.'
        fig.text(.06,.045,note,fontsize=9,color='#475569')
        limitation='Java heap only: excludes native allocations and peak RSS.' if allocation else 'Synthetic opaque fixtures; not photographs. Overlapping intervals do not establish a winner.'
        fig.text(.06,.02,limitation,fontsize=9,color='#475569')
        fig.subplots_adjust(left=.16,right=.97,top=.79,bottom=.13,wspace=.43,hspace=.7)
        name=operation+('-allocation' if allocation else '-time')
        fig.savefig(out/(name+'.png'),dpi=160,facecolor='white')
        fig.savefig(out/(name+'.svg'),facecolor='white',metadata={'Date':None})
        svg=out/(name+'.svg')
        svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines())+'\n')
        plt.close(fig)
print('Generated four PNG/SVG charts from all 20 benchmark cases.')
