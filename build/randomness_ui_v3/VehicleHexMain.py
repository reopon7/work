import os
import re
import sys
import math
import threading
import queue
from collections import Counter
import numpy as np
from tkinter import Tk, StringVar, IntVar, Text, END, NORMAL, DISABLED, filedialog, messagebox
from tkinter import ttk

from ApproximateEntropy import ApproximateEntropy as aet
from Complexity import ComplexityTest as ct
from CumulativeSum import CumulativeSums as cst
from FrequencyTest import FrequencyTest as ft
from Matrix import Matrix as mt
from RandomExcursions import RandomExcursions as ret
from RunTest import RunTest as rt
from Serial import Serial as serial
from Spectral import SpectralTest as st
from TemplateMatching import TemplateMatching as tm
from Universal import Universal as ut

ALPHA = 0.01
TOKEN_RE = re.compile(r"0[xX]([0-9A-Fa-f]{2})")

TEST_DEFS = [
    ("01. Frequency Test (Monobit)", ft.monobit_test),
    ("02. Frequency Test within a Block", ft.block_frequency),
    ("03. Run Test", rt.run_test),
    ("04. Longest Run of Ones in a Block", rt.longest_one_block_test),
    ("05. Binary Matrix Rank Test", mt.binary_matrix_rank_text),
    ("06. Discrete Fourier Transform (Spectral) Test", st.spectral_test),
    ("07. Non-Overlapping Template Matching Test", tm.non_overlapping_test),
    ("08. Overlapping Template Matching Test", tm.overlapping_patterns),
    ("09. Maurer's Universal Statistical Test", ut.statistical_test),
    ("10. Linear Complexity Test", ct.linear_complexity_test),
    ("11. Serial Test", serial.serial_test),
    ("12. Approximate Entropy Test", aet.approximate_entropy_test),
    ("13-1. Cumulative Sums (Forward) Test", cst.cumulative_sums_test),
    ("13-2. Cumulative Sums (Reverse) Test", cst.cumulative_sums_test),
    ("14. Random Excursions Test", ret.random_excursions_test),
    ("15. Random Excursions Variant Test", ret.variant_test),
]


def parse_hex_seed_file(path):
    seeds = []
    token_counts = Counter()
    with open(path, 'r', encoding='utf-8-sig') as f:
        for line_no, raw in enumerate(f, 1):
            line = raw.strip()
            if not line:
                continue
            matches = list(TOKEN_RE.finditer(line))
            if not matches:
                raise ValueError(f"Line {line_no}: no 0xNN byte tokens found")
            residue = TOKEN_RE.sub('', line)
            residue = residue.replace(',', ' ').replace(';', ' ').strip()
            if residue:
                raise ValueError(f"Line {line_no}: invalid HEX format near '{residue[:40]}'")
            data = bytes(int(m.group(1), 16) for m in matches)
            seeds.append((line_no, data))
            token_counts[len(data)] += 1
    if not seeds:
        raise ValueError('HEX seed file is empty')
    raw_bytes = b''.join(data for _, data in seeds)
    bitstream = ''.join(f'{b:08b}' for b in raw_bytes)
    counts = Counter(data for _, data in seeds)
    duplicate_groups = [(seed, cnt) for seed, cnt in counts.items() if cnt > 1]
    return {
        'seeds': seeds,
        'raw_bytes': raw_bytes,
        'bitstream': bitstream,
        'token_counts': token_counts,
        'duplicate_groups': duplicate_groups,
        'unique_count': len(counts),
    }


def run_tests(bitstream, selected=None, progress=None):
    if selected is None:
        selected = list(range(len(TEST_DEFS)))
    results = [None] * len(TEST_DEFS)
    total = len(selected)
    done = 0
    for idx in selected:
        label, fn = TEST_DEFS[idx]
        if progress:
            progress('before', idx, label, done, total)
        if idx == 13:
            result = fn(bitstream, mode=1)
        else:
            result = fn(bitstream)
        results[idx] = result
        done += 1
        if progress:
            progress('after', idx, label, done, total)
    return results


def result_bool_text(v):
    return 'Random' if bool(v) else 'Non-Random'


def safe_float_text(v):
    try:
        x = float(v)
        if math.isnan(x):
            return 'NaN'
        return f'{x:.15g}'
    except Exception:
        return str(v)


class App:
    def __init__(self, root):
        self.root = root
        self.q = queue.Queue()
        self.parsed = None
        self.results = [None] * len(TEST_DEFS)
        self.path_var = StringVar()
        self.redundancy_var = StringVar(value='Select a HEX seed file.')
        self.status_var = StringVar(value='Ready')
        self.selected = [IntVar(value=1) for _ in TEST_DEFS]
        self.pvars = [StringVar() for _ in TEST_DEFS]
        self.p2vars = [StringVar() for _ in TEST_DEFS]
        self.rvars = [StringVar() for _ in TEST_DEFS]
        self.r2vars = [StringVar() for _ in TEST_DEFS]
        self.exc_state = StringVar(value='+1')
        self.exc_x = StringVar()
        self.exc_p = StringVar()
        self.exc_r = StringVar()
        self.var_state = StringVar(value='-1.0')
        self.var_count = StringVar()
        self.var_p = StringVar()
        self.var_r = StringVar()
        self._configure_root()
        self._build_ui()
        self.exc_state.trace_add('write', lambda *_: self._refresh_excursion())
        self.var_state.trace_add('write', lambda *_: self._refresh_variant())

    def _configure_root(self):
        self.root.title('Test Suite for NIST Random Numbers')
        try:
            self.root.tk.call('tk', 'scaling', 96.0 / 72.0)
        except Exception:
            pass
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w = min(1660, max(1180, sw - 120))
        h = min(930, max(760, sh - 120))
        x = max(0, (sw - w)//2)
        y = max(0, (sh - h)//2 - 10)
        self.root.geometry(f'{w}x{h}+{x}+{y}')
        self.root.minsize(1120, 720)
        style = ttk.Style(self.root)
        style.configure('TLabel', font=('Segoe UI', 10))
        style.configure('TButton', font=('Segoe UI', 10), padding=(7, 3))
        style.configure('TCheckbutton', font=('Segoe UI', 10))
        style.configure('Hdr.TLabel', font=('Segoe UI Semibold', 10), anchor='center', relief='groove', padding=3)
        style.configure('Title.TLabel', font=('Segoe UI Semibold', 17), anchor='center')
        style.configure('TLabelframe.Label', font=('Segoe UI Semibold', 11))

    def _build_ui(self):
        outer = ttk.Frame(self.root, padding=10)
        outer.pack(fill='both', expand=True)
        outer.columnconfigure(0, weight=1)
        outer.rowconfigure(3, weight=1)

        ttk.Label(outer, text='A Statistical Test Suite for Random and Pseudorandom Number Generators for Cryptographic Applications', style='Title.TLabel').grid(row=0, column=0, sticky='ew', pady=(0, 7))

        inp = ttk.LabelFrame(outer, text='Input Data', padding=(10, 8))
        inp.grid(row=1, column=0, sticky='ew')
        inp.columnconfigure(1, weight=1)
        ttk.Label(inp, text='String Data File (HEX)').grid(row=0, column=0, sticky='w', padx=(0,10))
        ttk.Entry(inp, textvariable=self.path_var).grid(row=0, column=1, sticky='ew', padx=(0,10))
        ttk.Button(inp, text='Select String Data File (HEX)', command=self.select_file).grid(row=0, column=2, sticky='ew')

        red = ttk.LabelFrame(outer, text='Redundancy Check', padding=(10, 8))
        red.grid(row=2, column=0, sticky='ew', pady=(8,8))
        red.columnconfigure(1, weight=1)
        ttk.Label(red, text='Redundancy Check Result').grid(row=0, column=0, sticky='w', padx=(0,10))
        ttk.Entry(red, textvariable=self.redundancy_var, state='readonly').grid(row=0, column=1, sticky='ew')

        tests = ttk.LabelFrame(outer, text='Randomness Testing', padding=10)
        tests.grid(row=3, column=0, sticky='nsew')
        tests.columnconfigure(0, weight=1)
        tests.columnconfigure(1, weight=1)
        tests.rowconfigure(1, weight=1)

        left = ttk.Frame(tests)
        right = ttk.Frame(tests)
        left.grid(row=0, column=0, sticky='new', padx=(0,6))
        right.grid(row=0, column=1, sticky='new', padx=(6,0))
        self._build_result_column(left, [0,2,4,6,8,10,11,12])
        self._build_result_column(right, [1,3,5,7,9,13])

        bottom = ttk.Frame(tests)
        bottom.grid(row=1, column=0, columnspan=2, sticky='nsew', pady=(10,0))
        bottom.columnconfigure(0, weight=1)
        self._build_excursion(bottom)

        ctl = ttk.Frame(outer)
        ctl.grid(row=4, column=0, sticky='ew', pady=(8,0))
        for i,(text,cmd) in enumerate([
            ('Select All Test', self.select_all),
            ('De-Select All Test', self.deselect_all),
            ('Select Vehicle Test Item', self.select_vehicle),
            ('Execute Test', self.execute),
            ('Save as Text File', self.save_report),
            ('Reset', self.reset),
            ('Exit Program', self.root.destroy),
        ]):
            b=ttk.Button(ctl, text=text, command=cmd)
            b.grid(row=0, column=i, padx=(0,6), sticky='w')
            if text=='Execute Test': self.execute_btn=b

        logframe = ttk.Frame(outer)
        logframe.grid(row=5, column=0, sticky='ew', pady=(8,0))
        logframe.columnconfigure(0, weight=1)
        self.log = Text(logframe, height=4, font=('Consolas', 9), wrap='none')
        self.log.grid(row=0, column=0, sticky='ew')
        sb=ttk.Scrollbar(logframe, orient='vertical', command=self.log.yview)
        sb.grid(row=0,column=1,sticky='ns')
        self.log.configure(yscrollcommand=sb.set)
        ttk.Label(logframe, textvariable=self.status_var).grid(row=1, column=0, sticky='w', pady=(4,2))
        self.progress = ttk.Progressbar(logframe, mode='determinate')
        self.progress.grid(row=2, column=0, columnspan=2, sticky='ew')

    def _build_result_column(self, parent, indices):
        parent.columnconfigure(0, weight=3)
        parent.columnconfigure(1, weight=2)
        parent.columnconfigure(2, weight=1)
        ttk.Label(parent, text='Test Type', style='Hdr.TLabel').grid(row=0,column=0,sticky='ew')
        ttk.Label(parent, text='P-Value', style='Hdr.TLabel').grid(row=0,column=1,sticky='ew',padx=(6,6))
        ttk.Label(parent, text='Result', style='Hdr.TLabel').grid(row=0,column=2,sticky='ew')
        for r, idx in enumerate(indices, 1):
            ttk.Checkbutton(parent, text=TEST_DEFS[idx][0], variable=self.selected[idx]).grid(row=r,column=0,sticky='w',pady=2)
            if idx == 10:
                box=ttk.Frame(parent); box.grid(row=r,column=1,sticky='ew',padx=(6,6)); box.columnconfigure(0,weight=1); box.columnconfigure(1,weight=1)
                ttk.Entry(box,textvariable=self.pvars[idx],state='readonly').grid(row=0,column=0,sticky='ew',padx=(0,2))
                ttk.Entry(box,textvariable=self.p2vars[idx],state='readonly').grid(row=0,column=1,sticky='ew',padx=(2,0))
                rbox=ttk.Frame(parent); rbox.grid(row=r,column=2,sticky='ew'); rbox.columnconfigure(0,weight=1); rbox.columnconfigure(1,weight=1)
                ttk.Entry(rbox,textvariable=self.rvars[idx],state='readonly').grid(row=0,column=0,sticky='ew',padx=(0,2))
                ttk.Entry(rbox,textvariable=self.r2vars[idx],state='readonly').grid(row=0,column=1,sticky='ew',padx=(2,0))
            else:
                ttk.Entry(parent,textvariable=self.pvars[idx],state='readonly').grid(row=r,column=1,sticky='ew',padx=(6,6),pady=2)
                ttk.Entry(parent,textvariable=self.rvars[idx],state='readonly').grid(row=r,column=2,sticky='ew',pady=2)

    def _build_excursion(self, parent):
        exc = ttk.Frame(parent)
        exc.grid(row=0,column=0,sticky='ew')
        exc.columnconfigure(2,weight=2); exc.columnconfigure(3,weight=2); exc.columnconfigure(4,weight=1)
        ttk.Checkbutton(exc,text=TEST_DEFS[14][0],variable=self.selected[14]).grid(row=0,column=0,columnspan=5,sticky='w')
        for c,t in enumerate(['State','CHI-SQUARED','P-Value','Conclusion']):
            ttk.Label(exc,text=t,style='Hdr.TLabel').grid(row=1,column=c,sticky='ew',padx=(0 if c==0 else 6,0))
        self.exc_combo=ttk.Combobox(exc,textvariable=self.exc_state,values=['-4','-3','-2','-1','+1','+2','+3','+4'],state='readonly',width=8)
        self.exc_combo.grid(row=2,column=0,sticky='ew')
        ttk.Entry(exc,textvariable=self.exc_x,state='readonly').grid(row=2,column=1,sticky='ew',padx=(6,0))
        ttk.Entry(exc,textvariable=self.exc_p,state='readonly').grid(row=2,column=2,sticky='ew',padx=(6,0))
        ttk.Entry(exc,textvariable=self.exc_r,state='readonly').grid(row=2,column=3,sticky='ew',padx=(6,0))
        ttk.Button(exc,text='Update',command=self._refresh_excursion).grid(row=2,column=4,sticky='ew',padx=(6,0))

        var = ttk.Frame(parent)
        var.grid(row=1,column=0,sticky='ew',pady=(10,0))
        var.columnconfigure(2,weight=2); var.columnconfigure(3,weight=2); var.columnconfigure(4,weight=1)
        ttk.Checkbutton(var,text=TEST_DEFS[15][0],variable=self.selected[15]).grid(row=0,column=0,columnspan=5,sticky='w')
        for c,t in enumerate(['State','Count','P-Value','Conclusion']):
            ttk.Label(var,text=t,style='Hdr.TLabel').grid(row=1,column=c,sticky='ew',padx=(0 if c==0 else 6,0))
        vals=[f'{i:.1f}' for i in range(-9,0)] + [f'+{i:.1f}' for i in range(1,10)]
        self.var_combo=ttk.Combobox(var,textvariable=self.var_state,values=vals,state='readonly',width=8)
        self.var_combo.grid(row=2,column=0,sticky='ew')
        ttk.Entry(var,textvariable=self.var_count,state='readonly').grid(row=2,column=1,sticky='ew',padx=(6,0))
        ttk.Entry(var,textvariable=self.var_p,state='readonly').grid(row=2,column=2,sticky='ew',padx=(6,0))
        ttk.Entry(var,textvariable=self.var_r,state='readonly').grid(row=2,column=3,sticky='ew',padx=(6,0))
        ttk.Button(var,text='Update',command=self._refresh_variant).grid(row=2,column=4,sticky='ew',padx=(6,0))

    def _log(self, msg):
        self.log.insert(END, msg+'\n'); self.log.see(END)

    def select_file(self):
        path=filedialog.askopenfilename(title='Select HEX Seed File',filetypes=[('Text files','*.txt'),('All files','*.*')])
        if not path: return
        self.path_var.set(path)
        try:
            self.parsed=parse_hex_seed_file(path)
            n=len(self.parsed['seeds']); u=self.parsed['unique_count']; bits=len(self.parsed['bitstream'])
            widths=', '.join(f'{k}B×{v}' for k,v in sorted(self.parsed['token_counts'].items()))
            dups=self.parsed['duplicate_groups']
            if dups:
                self.redundancy_var.set(f'FAIL: {len(dups)} duplicate seed value(s) | {n} seeds / {u} unique | {widths} | {bits:,} bits')
            else:
                self.redundancy_var.set(f'Pass, no redundancies | {n:,} seeds / {u:,} unique | {widths} | {bits:,} bits')
            self._log(f'HEX loaded: {os.path.basename(path)} | {n:,} seeds | {len(self.parsed["raw_bytes"]):,} bytes | {bits:,} bits')
        except Exception as e:
            self.parsed=None
            self.redundancy_var.set('ERROR: '+str(e))
            messagebox.showerror('HEX Input Error',str(e))

    def select_all(self):
        for v in self.selected: v.set(1)

    def deselect_all(self):
        for v in self.selected: v.set(0)

    def select_vehicle(self):
        for v in self.selected: v.set(1)
        self._log('Vehicle preset: selected all NIST items visible in the reference tool.')

    def reset(self):
        for v in self.pvars+self.p2vars+self.rvars+self.r2vars: v.set('')
        self.exc_x.set(''); self.exc_p.set(''); self.exc_r.set('')
        self.var_count.set(''); self.var_p.set(''); self.var_r.set('')
        self.results=[None]*len(TEST_DEFS)
        self.progress['value']=0
        self.status_var.set('Ready')
        self.log.delete('1.0',END)

    def execute(self):
        if self.parsed is None:
            p=self.path_var.get().strip()
            if not p:
                messagebox.showwarning('Warning','Select a String Data File (HEX) first.'); return
            try: self.parsed=parse_hex_seed_file(p)
            except Exception as e: messagebox.showerror('HEX Input Error',str(e)); return
        selected=[i for i,v in enumerate(self.selected) if v.get()]
        if not selected:
            messagebox.showwarning('Warning','No tests selected.'); return
        self.execute_btn.configure(state=DISABLED)
        self.progress['maximum']=len(selected); self.progress['value']=0
        self.status_var.set(f'Running {len(selected)} selected test item(s)...')
        self._log('Test Start')
        for idx in reversed(selected): self._log(f'Wait, calculating : {TEST_DEFS[idx][0]} [selected]')
        threading.Thread(target=self._worker,args=(self.parsed['bitstream'],selected),daemon=True).start()
        self.root.after(80,self._poll)

    def _worker(self, bitstream, selected):
        def progress(kind,idx,label,done,total):
            if kind=='after': self.q.put(('progress',idx,label,done,total))
        try:
            r=run_tests(bitstream,selected,progress)
            self.q.put(('complete',r))
        except Exception as e:
            self.q.put(('error',str(e)))

    def _poll(self):
        try:
            while True:
                m=self.q.get_nowait()
                if m[0]=='progress':
                    _,idx,label,done,total=m
                    self.progress['value']=done
                    self.status_var.set(f'Running {done}/{total}: {label}')
                elif m[0]=='complete':
                    self.results=m[1]; self._write_results(); self.progress['value']=self.progress['maximum']
                    self.status_var.set('Test Complete'); self._log('Test Complete')
                    self.execute_btn.configure(state=NORMAL); messagebox.showinfo('Execute','Test Run Complete.'); return
                elif m[0]=='error':
                    self.status_var.set('Error'); self._log('ERROR: '+m[1]); self.execute_btn.configure(state=NORMAL); messagebox.showerror('Error',m[1]); return
        except queue.Empty:
            self.root.after(80,self._poll)

    def _write_results(self):
        for i,r in enumerate(self.results):
            if r is None: continue
            if i==10:
                try:
                    self.pvars[i].set(safe_float_text(r[0][0])); self.rvars[i].set(result_bool_text(r[0][1]))
                    self.p2vars[i].set(safe_float_text(r[1][0])); self.r2vars[i].set(result_bool_text(r[1][1]))
                except Exception: pass
            elif i in (14,15):
                continue
            else:
                try: self.pvars[i].set(safe_float_text(r[0])); self.rvars[i].set(result_bool_text(r[1]))
                except Exception:
                    self.pvars[i].set(str(r[0]) if isinstance(r,(tuple,list)) and r else ''); self.rvars[i].set('Non-Random')
        self._refresh_excursion(); self._refresh_variant()

    def _refresh_excursion(self):
        r=self.results[14]
        if not r: return
        target=self.exc_state.get()
        for row in r:
            if str(row[0])==target:
                self.exc_x.set(safe_float_text(row[2])); self.exc_p.set(safe_float_text(row[3])); self.exc_r.set(result_bool_text(row[4])); return
        self.exc_x.set(''); self.exc_p.set(''); self.exc_r.set('')

    def _refresh_variant(self):
        r=self.results[15]
        if not r: return
        target=self.var_state.get().replace('.0','')
        for row in r:
            if str(row[0]).replace('.0','')==target:
                self.var_count.set(str(int(row[2]))); self.var_p.set(safe_float_text(row[3])); self.var_r.set(result_bool_text(row[4])); return
        self.var_count.set(''); self.var_p.set(''); self.var_r.set('')

    def save_report(self):
        if not any(r is not None for r in self.results):
            messagebox.showwarning('Save Warning','No test results available to save.'); return
        path=filedialog.asksaveasfilename(defaultextension='.txt',filetypes=[('Text files','*.txt'),('All files','*.*')])
        if not path: return
        with open(path,'w',encoding='utf-8') as f:
            f.write('NIST Randomness Test Suite - HEX Seed Report\n')
            f.write(f'Source: {self.path_var.get()}\n')
            if self.parsed:
                f.write(f'Seeds: {len(self.parsed["seeds"])}\nBytes: {len(self.parsed["raw_bytes"])}\nBits: {len(self.parsed["bitstream"])}\n')
                f.write(f'Unique seeds: {self.parsed["unique_count"]}\nDuplicate groups: {len(self.parsed["duplicate_groups"])}\n')
            f.write(f'Alpha: {ALPHA}\n\n')
            for i,(label,_) in enumerate(TEST_DEFS):
                r=self.results[i]
                if r is None: continue
                f.write(label+'\n')
                f.write(repr(r)+'\n\n')
        self._log(f'Report saved: {path}')


def self_test(path):
    p=parse_hex_seed_file(path)
    print(f"seeds={len(p['seeds'])} unique={p['unique_count']} bytes={len(p['raw_bytes'])} bits={len(p['bitstream'])} duplicates={len(p['duplicate_groups'])}")
    results=run_tests(p['bitstream'])
    for i,r in enumerate(results):
        print(TEST_DEFS[i][0], repr(r))
    return 0


def main():
    if len(sys.argv)>=3 and sys.argv[1]=='--self-test':
        return self_test(sys.argv[2])
    root=Tk(); App(root); root.mainloop(); return 0

if __name__=='__main__':
    raise SystemExit(main())
