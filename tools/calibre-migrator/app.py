"""Small Windows GUI. No credentials, no network, no modification of source."""
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from pathlib import Path
import queue
import threading
import os
import sys
from migrator import scan, migrate, choose_files, summarize_report


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("BibliotecaAI · Migrar desde Calibre")
        self.geometry("740x470")
        self.minsize(600, 380)
        self.source = tk.StringVar()
        self.output = tk.StringVar()
        self.all_formats = tk.BooleanVar(value=True)
        self.status = tk.StringVar(value="Selecciona la carpeta raíz de tu biblioteca Calibre.")
        self.events = queue.Queue()
        self.stop = threading.Event()
        self.busy = False
        panel = ttk.Frame(self, padding=20)
        panel.pack(fill="both", expand=True)
        panel.columnconfigure(0, weight=1)
        ttk.Label(panel, text="Migrar biblioteca de Calibre", font=("Segoe UI", 17, "bold")).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 16))
        for row, label, var in [(1, "Biblioteca de origen (MiBiblioteca)", self.source), (3, "Carpeta temporal de intercambio (fuera de la biblioteca activa)", self.output)]:
            ttk.Label(panel, text=label).grid(row=row,column=0,columnspan=2,sticky="w")
            ttk.Entry(panel,textvariable=var).grid(row=row+1,column=0,sticky="ew",pady=(3,12))
            ttk.Button(panel,text="Examinar…",command=lambda v=var:self.pick(v)).grid(row=row+1,column=1,padx=(8,0),pady=(3,12))
        ttk.Checkbutton(panel,text="Conservar todos los formatos compatibles (si no: EPUB > MOBI)",variable=self.all_formats).grid(row=5,column=0,columnspan=2,sticky="w")
        ttk.Label(panel,text="1. Cierra Calibre y analiza. 2. Prepara los paquetes en una carpeta temporal.\n3. En BibliotecaAI Android, Ajustes → Importar biblioteca de Calibre, selecciona cada ZIP. No sustituir la copia de seguridad ni copiar los ZIP a _Sincronizacion.",wraplength=620).grid(row=6,column=0,columnspan=2,sticky="w",pady=12)
        buttons=ttk.Frame(panel)
        buttons.grid(row=7,column=0,columnspan=2,sticky="w")
        self.preview=ttk.Button(buttons,text="Analizar",command=lambda:self.start(False)); self.preview.pack(side="left")
        self.go=ttk.Button(buttons,text="Preparar para BibliotecaAI",command=lambda:self.start(True)); self.go.pack(side="left",padx=8)
        self.cancel=ttk.Button(buttons,text="Cancelar",command=self.stop.set,state="disabled"); self.cancel.pack(side="left")
        self.bar=ttk.Progressbar(panel,mode="indeterminate")
        self.bar.grid(row=8,column=0,columnspan=2,sticky="ew",pady=14)
        ttk.Label(panel,textvariable=self.status,wraplength=620).grid(row=9,column=0,columnspan=2,sticky="w")
        self.protocol("WM_DELETE_WINDOW",self.close)
        self.after(100,self.poll)

    def pick(self,var):
        if self.busy: return
        path=filedialog.askdirectory()
        if path: var.set(path)

    def close(self):
        if self.busy:
            if messagebox.askyesno("Cancelar", "¿Cancelar la operación? Los ZIP ya verificados se conservan."):
                self.stop.set()
            return
        self.destroy()

    def start(self, create):
        source, output, all_formats=self.source.get().strip(),self.output.get().strip(),self.all_formats.get()
        if not source or (create and not output):
            messagebox.showwarning("Faltan carpetas","Selecciona origen y destino.")
            return
        if self.busy: return
        self.busy=True; self.stop.clear()
        self.preview.configure(state="disabled"); self.go.configure(state="disabled"); self.cancel.configure(state="normal")
        self.bar.start(15)
        def job():
            try:
                if create:
                    run,count,books=migrate(source,output,all_formats,lambda msg:self.events.put(("status",msg)),self.stop.is_set)
                    self.events.put(("done",(run,count,books,summarize_report(run))))
                else:
                    records,warnings=scan(source)
                    count=sum(len(choose_files(r,all_formats)) for r in records)
                    self.events.put(("preview",f"{len(records)} fichas; {count} archivos candidatos; {len(warnings)} avisos. Se verificará formato y hash durante la migración."))
            except Exception as exc:
                self.events.put(("error",str(exc)))
        threading.Thread(target=job,daemon=True).start()

    def poll(self):
        try:
            while True:
                kind,value=self.events.get_nowait()
                if kind=="status":
                    self.status.set(value); continue
                self.busy=False; self.bar.stop()
                self.preview.configure(state="normal"); self.go.configure(state="normal"); self.cancel.configure(state="disabled")
                if kind=="done":
                    run,count,books,summary=value
                    result = f"{books} originales preparados en {count} ZIP; {summary['duplicate']} duplicados; {summary['error']} errores; {summary['unsupported']} fichas sin formato; {summary['warning']} avisos."
                    self.status.set(result + " Consulta Informe.csv. Falta incorporarlos desde Android.")
                    if messagebox.askyesno("Paquetes preparados (no importados aún)", result + "\nEn Android, usa Ajustes → Importar biblioteca de Calibre.\n¿Abrir carpeta de salida?"):
                        os.startfile(str(run))
                elif kind=="error":
                    self.status.set(value); messagebox.showwarning("Operación no completada",value+"\nConsulta la carpeta de destino si hay paquetes anteriores.")
                else:
                    self.status.set(value)
        except queue.Empty:
            pass
        self.after(100,self.poll)


if __name__=="__main__":
    if "--self-test" in sys.argv:
        import io
        import unittest
        import test_migrator
        window = App()
        window.withdraw()
        window.update_idletasks()
        window.destroy()
        suite = unittest.defaultTestLoader.loadTestsFromModule(test_migrator)
        result = unittest.TextTestRunner(stream=io.StringIO()).run(suite)
        sys.exit(0 if result.wasSuccessful() else 1)
    App().mainloop()
