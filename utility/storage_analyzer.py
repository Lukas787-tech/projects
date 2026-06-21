import os
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import subprocess

def get_size(path):
    total_size = 0
    try:
        if os.path.isfile(path):
            total_size = os.path.getsize(path)
        elif os.path.isdir(path):
            for dirpath, _, filenames in os.walk(path):
                for f in filenames:
                    fp = os.path.join(dirpath, f)
                    if not os.path.islink(fp):
                        total_size += os.path.getsize(fp)
    except (PermissionError, FileNotFoundError):
        pass
    return total_size

def format_size(size):
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if size < 1024.0:
            return f"{size:.2f} {unit}"
        size /= 1024.0

class StorageAnalyzerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Storage Analyzer Utility")
        self.root.geometry("700x450")

        self.current_path = tk.StringVar(value=os.path.expanduser("~"))

        # Top frame
        top_frame = tk.Frame(root)
        top_frame.pack(fill=tk.X, padx=10, pady=10)

        tk.Label(top_frame, text="Directory:").pack(side=tk.LEFT)
        tk.Entry(top_frame, textvariable=self.current_path, width=60).pack(side=tk.LEFT, padx=5)
        tk.Button(top_frame, text="Browse", command=self.browse).pack(side=tk.LEFT)
        tk.Button(top_frame, text="Analyze", command=self.analyze, bg="lightblue").pack(side=tk.LEFT, padx=5)

        # Treeview
        columns = ("name", "size", "type", "path")
        self.tree = ttk.Treeview(root, columns=columns, show="headings")
        self.tree.heading("name", text="Name")
        self.tree.heading("size", text="Size")
        self.tree.heading("type", text="Type")
        self.tree.heading("path", text="Full Path")

        self.tree.column("name", width=200)
        self.tree.column("size", width=80, anchor=tk.E)
        self.tree.column("type", width=80)
        self.tree.column("path", width=300)

        self.tree.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        # Double click to open folder
        self.tree.bind("<Double-1>", self.on_double_click)

        # Bottom frame
        bottom_frame = tk.Frame(root)
        bottom_frame.pack(fill=tk.X, padx=10, pady=10)
        tk.Button(bottom_frame, text="Open Selected in Explorer", command=self.open_selected).pack(side=tk.LEFT)
        self.status_var = tk.StringVar(value="Ready.")
        tk.Label(bottom_frame, textvariable=self.status_var, fg="gray").pack(side=tk.RIGHT)

    def browse(self):
        folder = filedialog.askdirectory(initialdir=self.current_path.get())
        if folder:
            self.current_path.set(folder)

    def analyze(self):
        target_dir = self.current_path.get()
        if not os.path.isdir(target_dir):
            messagebox.showerror("Error", "Invalid directory path.")
            return

        self.status_var.set("Analyzing... please wait. (This might take a while for large drives)")
        self.root.update()

        # Clear tree
        for item in self.tree.get_children():
            self.tree.delete(item)

        items = []
        try:
            for entry in os.scandir(target_dir):
                size = get_size(entry.path)
                item_type = "Folder" if entry.is_dir() else "File"
                items.append({
                    "name": entry.name,
                    "size": size,
                    "type": item_type,
                    "path": entry.path
                })
        except PermissionError:
            messagebox.showerror("Error", "Permission denied to read this directory.")
            self.status_var.set("Error.")
            return

        # Sort by size descending
        items.sort(key=lambda x: x["size"], reverse=True)

        for item in items:
            self.tree.insert("", tk.END, values=(
                item["name"], 
                format_size(item["size"]), 
                item["type"], 
                item["path"]
            ))

        self.status_var.set(f"Done. Analyzed {len(items)} items in {target_dir}")

    def on_double_click(self, event):
        self.open_selected()

    def open_selected(self):
        selected = self.tree.selection()
        if not selected:
            return
        
        item = self.tree.item(selected[0])
        path = item["values"][3]
        
        if os.path.exists(path):
            try:
                # Open in file explorer
                if os.name == 'nt':
                    os.startfile(path)
                else:
                    subprocess.call(["xdg-open", path])
            except Exception as e:
                messagebox.showerror("Error", f"Failed to open path: {e}")

if __name__ == "__main__":
    root = tk.Tk()
    app = StorageAnalyzerApp(root)
    root.mainloop()
