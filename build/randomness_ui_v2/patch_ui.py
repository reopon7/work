from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else '.')

def rep(s,a,b):
    if a not in s: raise RuntimeError('pattern not found: '+a[:80])
    return s.replace(a,b)

p=root/'GUI.py'; s=p.read_text(encoding='utf-8')
s=rep(s,'from tkinter import StringVar\n','''from tkinter import StringVar\n\n_UI_SCALE = 1.0\n\ndef set_ui_scale(scale):\n    global _UI_SCALE\n    _UI_SCALE = max(0.80, min(float(scale), 1.35))\n\ndef px(value):\n    return max(1, int(round(float(value) * _UI_SCALE)))\n\ndef ui_font(size_px, family="Segoe UI"):\n    # Negative sizes are pixel sizes: stable at Windows 100/125/150% DPI.\n    return (family, -max(9, int(round(float(size_px) * _UI_SCALE))))\n''')
R=[
('self.button = ttk.Button(master, text=title, command=action)\n        self.button.place(x=x_coor, y=y_coor, width=width, height=25)','self.button = ttk.Button(master, text=title, command=action, style="App.TButton")\n        self.button.place(x=px(x_coor), y=px(y_coor), width=px(width), height=px(25))'),
('label = ttk.Label(master, text=title, font=("Calibri", 12))\n        label.place(x=x_coor, y=y_coor, height=25)','label = ttk.Label(master, text=title, font=ui_font(13))\n        label.place(x=px(x_coor), y=px(y_coor), height=px(25))'),
('self.__data_entry = ttk.Entry(master, textvariable=self.__data, font=("Calibri", 10))\n        self.__data_entry.place(x=150, y=y_coor, width=900, height=25)','self.__data_entry = ttk.Entry(master, textvariable=self.__data, font=ui_font(13))\n        entry_width = max(100, button_xcoor - 160) if has_button else 1070\n        self.__data_entry.place(x=px(150), y=px(y_coor), width=px(entry_width), height=px(25))'),
('button = ttk.Button(master, text=button_title, command=action)\n            button.place(x=button_xcoor, y=y_coor, width=180, height=25)','button = ttk.Button(master, text=button_title, command=action, style="App.TButton")\n            button.place(x=px(button_xcoor), y=px(y_coor), width=px(button_width), height=px(25))'),
("def __init__(self, master, title, x_coor, y_coor, width, font_size=18, border=0, relief='flat'):","def __init__(self, master, title, x_coor, y_coor, width, font_size=18, border=0, relief='flat', anchor='w'):"),
('label = ttk.Label(master, text=title, borderwidth=border, relief=relief, font=("Calibri", font_size))\n        label.place(x=x_coor, y=y_coor, width=width, height=25)','label = ttk.Label(master, text=title, borderwidth=border, relief=relief, font=ui_font(font_size), anchor=anchor)\n        label.place(x=px(x_coor), y=px(y_coor), width=px(width), height=px(25))'),
('label = ttk.Label(master, text=title, font=("Calibri", 12))\n        label.place(x=x_coor, y=y_coor, height=25, width=100)','label = ttk.Label(master, text=title, font=ui_font(13))\n        label.place(x=px(x_coor), y=px(y_coor), height=px(25), width=px(100))'),
('self.__option.place(x=150, y=y_coor, height=25, width=width)','self.__option.place(x=px(150), y=px(y_coor), height=px(25), width=px(width))'),
('checkbox = ttk.Checkbutton(master, text=title, variable=self.__chb_var)\n        checkbox.place(x=x_coor, y=y_coor)','checkbox = ttk.Checkbutton(master, text=title, variable=self.__chb_var, style="App.TCheckbutton")\n        checkbox.place(x=px(x_coor), y=px(y_coor), height=px(25))'),
('font=("Calibri", font_size)','font=ui_font(font_size)'),
('p_value_entry.place(x=p_value_x_coor, y=y_coor, width=p_value_width, height=25)','p_value_entry.place(x=px(p_value_x_coor), y=px(y_coor), width=px(p_value_width), height=px(25))'),
('result_entry.place(x=result_x_coor, y=y_coor, width=result_width, height=25)','result_entry.place(x=px(result_x_coor), y=px(y_coor), width=px(result_width), height=px(25))'),
('p_value_entry_02.place(x=875, y=y_coor, width=235, height=25)','p_value_entry_02.place(x=px(900), y=px(y_coor), width=px(210), height=px(25))'),
('result_entry_02.place(x=1115, y=y_coor, width=110, height=25)','result_entry_02.place(x=px(1115), y=px(y_coor), width=px(110), height=px(25))'),
('p_value_entry_02.place(x=p_value_x_coor, y=y_coor+25, width=p_value_width, height=25)','p_value_entry_02.place(x=px(p_value_x_coor), y=px(y_coor+25), width=px(p_value_width), height=px(25))'),
('result_entry_02.place(x=result_x_coor, y=y_coor+25, width=result_width, height=25)','result_entry_02.place(x=px(result_x_coor), y=px(y_coor+25), width=px(result_width), height=px(25))'),
('state_option.place(x=(x_coor + 60), y=(y_coor + 60), height=25, width=100)','state_option.place(x=px(x_coor + 60), y=px(y_coor + 60), height=px(25), width=px(100))'),
('entry_font = ("Calibri", font_size)','entry_font = ui_font(font_size)'),
('xObs_Entry.place(x=(x_coor + 165), y=(y_coor + 60), width=350, height=25)','xObs_Entry.place(x=px(x_coor + 165), y=px(y_coor + 60), width=px(350), height=px(25))'),
('count_Entry.place(x=(x_coor + 165), y=(y_coor + 60), width=350, height=25)','count_Entry.place(x=px(x_coor + 165), y=px(y_coor + 60), width=px(350), height=px(25))'),
('p_value_Entry.place(x=(x_coor + 520), y=(y_coor + 60), width=350, height=25)','p_value_Entry.place(x=px(x_coor + 520), y=px(y_coor + 60), width=px(350), height=px(25))'),
('conclusion_Entry.place(x=(x_coor + 875), y=(y_coor + 60), width=150, height=25)','conclusion_Entry.place(x=px(x_coor + 875), y=px(y_coor + 60), width=px(150), height=px(25))')]
for a,b in R: s=rep(s,a,b)
p.write_text(s,encoding='utf-8')

p=root/'Main.py'; s=p.read_text(encoding='utf-8')
s=rep(s,'from GUI import TestItem\n','from GUI import TestItem\nfrom GUI import set_ui_scale, px, ui_font\n')
R=[
("title_label = LabelTag(self.master, frame_title, 0, 5, 1260)","title_label = LabelTag(self.master, frame_title, 10, 4, 1240, font_size=20, anchor='center')"),
('input_label_frame.config(font=("Calibri", 14))','input_label_frame.config(font=ui_font(15))'),
('input_label_frame.place(x=20, y=30, width=1260, height=125)','input_label_frame.place(x=px(10), y=px(30), width=px(1260), height=px(120))'),
('self._stest_selection_label_frame.config(font=("Calibri", 14))','self._stest_selection_label_frame.config(font=ui_font(15))'),
('self._stest_selection_label_frame.place(x=20, y=155, width=1260, height=450)','self._stest_selection_label_frame.place(x=px(10), y=px(150), width=px(1260), height=px(455))'),
("'Test Type', 10, 5, 250, 11","'Test Type', 10, 5, 275, 12"),
("'P-Value', 265, 5, 235, 11","'P-Value', 290, 5, 210, 12"),
("'Result', 505, 5, 110, 11","'Result', 505, 5, 110, 12"),
("'Test Type', 620, 5, 250, 11","'Test Type', 620, 5, 275, 12"),
("'P-Value', 875, 5, 235, 11","'P-Value', 900, 5, 210, 12"),
("'Result', 1115, 5, 110, 11","'Result', 1115, 5, 110, 12"),
('p_value_x_coor=265, p_value_width=235, result_x_coor=505, result_width=110, font_size=11','p_value_x_coor=290, p_value_width=210, result_x_coor=505, result_width=110, font_size=13'),
('p_value_x_coor=875, p_value_width=235, result_x_coor=1115, result_width=110, font_size=11','p_value_x_coor=900, p_value_width=210, result_x_coor=1115, result_width=110, font_size=13'),
("['-4', '-3', '-2', '-1', '+1', '+2', '+3', '+4'], font_size=11)","['-4', '-3', '-2', '-1', '+1', '+2', '+3', '+4'], font_size=13)"),
("'+9.0'], variant=True, font_size=11)","'+9.0'], variant=True, font_size=13)"),
("'Select All Test', 20, 615","'Select All Test', 10, 612"),
("'De-Select All Test', 125, 615","'De-Select All Test', 115, 612"),
("'Execute Test', 280, 615","'Execute Test', 270, 612"),
("'Save as Text File', 385, 615","'Save as Text File', 375, 612"),
("'Reset', 490, 615","'Reset', 480, 612"),
("'Exit Program', 595, 615","'Exit Program', 585, 612"),
('status_frame.place(x=20, y=635, width=1260, height=40)','status_frame.place(x=px(10), y=px(640), width=px(1260), height=px(30))'),
('font=("Calibri", 10)','font=ui_font(11)'),
('length=1260','length=px(1260)')]
for a,b in R: s=rep(s,a,b)
old='''if __name__ == '__main__':\n    np.seterr('raise') # Make exceptions fatal, otherwise GUI might get inconsistent\n    root = Tk()\n    root.resizable(0, 0)\n    root.geometry("%dx%d+0+0" % (1300, 650)) # Reverted window height\n    title = 'Test Suite for NIST Random Numbers'\n    root.title(title)\n    app = Main(root)\n    app.focus_displayof()\n    app.mainloop()'''
new='''if __name__ == '__main__':\n    np.seterr('raise') # Make exceptions fatal, otherwise GUI might get inconsistent\n    root = Tk()\n    try:\n        root.tk.call('tk', 'scaling', 96.0 / 72.0)\n    except Exception:\n        pass\n    screen_w = max(1024, root.winfo_screenwidth())\n    screen_h = max(700, root.winfo_screenheight())\n    base_w, base_h = 1280, 675\n    fit_scale = min((screen_w - 16) / base_w, (screen_h - 48) / base_h, 1.25)\n    fit_scale = max(0.86, fit_scale)\n    set_ui_scale(fit_scale)\n    win_w = int(round(base_w * fit_scale))\n    win_h = int(round(base_h * fit_scale))\n    x = max(0, (screen_w - win_w) // 2)\n    y = max(0, (screen_h - win_h - 24) // 2)\n    root.resizable(True, True)\n    root.minsize(min(win_w, 1100), min(win_h, 590))\n    root.geometry(f"{win_w}x{win_h}+{x}+{y}")\n    title = 'Test Suite for NIST Random Numbers'\n    root.title(title)\n    style = ttk.Style(root)\n    style.configure('App.TButton', font=ui_font(12), padding=(4, 1))\n    style.configure('App.TCheckbutton', font=ui_font(12))\n    style.configure('TEntry', padding=(2, 0))\n    app = Main(root)\n    app.focus_displayof()\n    app.mainloop()'''
s=rep(s,old,new)
p.write_text(s,encoding='utf-8')
