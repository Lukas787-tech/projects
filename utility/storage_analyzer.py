import os
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, simpledialog
import subprocess
import threading
import time
import shutil

def format_size(size):
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if size < 1024.0:
            return f"{size:.2f} {unit}"
        size /= 1024.0

class StorageAnalyzerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Storage Analyzer Pro Utility")
        self.root.geometry("850x550")
        self.root.minsize(600, 400)

        # Apply a cleaner theme
        style = ttk.Style(root)
        if "clam" in style.theme_names():
            style.theme_use("clam")
        
        style.configure("Treeview", rowheight=25, font=('Segoe UI', 10))
        style.configure("Treeview.Heading", font=('Segoe UI', 10, 'bold'), background="#d3d3d3")
        
        self.current_path = tk.StringVar(value=os.path.expanduser("~"))
        self.is_scanning = False
        self.cancel_scan = False

        self.setup_ui()

    def setup_ui(self):
        # Top frame for controls
        top_frame = ttk.Frame(self.root, padding=10)
        top_frame.pack(fill=tk.X)

        ttk.Label(top_frame, text="Directory:", font=('Segoe UI', 10)).pack(side=tk.LEFT, padx=(0, 5))
        self.entry_path = ttk.Entry(top_frame, textvariable=self.current_path, width=50, font=('Segoe UI', 10))
        self.entry_path.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=5)
        
        # Bind enter key
        self.entry_path.bind('<Return>', lambda e: self.start_analysis())

        ttk.Button(top_frame, text="Browse...", command=self.browse).pack(side=tk.LEFT, padx=2)
        ttk.Button(top_frame, text="Up Level", command=self.go_up).pack(side=tk.LEFT, padx=2)
        
        self.btn_analyze = tk.Button(top_frame, text="Analyze", command=self.start_analysis, bg="#4CAF50", fg="white", font=('Segoe UI', 10, 'bold'), relief="flat", padx=10)
        self.btn_analyze.pack(side=tk.LEFT, padx=10)

        # Main content area
        main_frame = ttk.Frame(self.root, padding=10)
        main_frame.pack(fill=tk.BOTH, expand=True)

        # Treeview with scrollbar
        columns = ("name", "size", "type", "path")
        self.tree = ttk.Treeview(main_frame, columns=columns, show="headings", selectmode="extended")
        self.tree.heading("name", text="Name", command=lambda: self.sort_tree("name", False))
        self.tree.heading("size", text="Size \u25BC", command=lambda: self.sort_tree("size", True))
        self.tree.heading("type", text="Type", command=lambda: self.sort_tree("type", False))
        self.tree.heading("path", text="Full Path")

        self.tree.column("name", width=250, minwidth=150)
        self.tree.column("size", width=100, minwidth=80, anchor=tk.E)
        self.tree.column("type", width=100, minwidth=80)
        self.tree.column("path", width=350, minwidth=200)

        scrollbar = ttk.Scrollbar(main_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscroll=scrollbar.set)
        
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # Context Menu
        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Open in Explorer", command=self.open_selected)
        self.menu.add_command(label="Open Folder Path", command=self.open_selected_dir)
        self.menu.add_separator()
        self.menu.add_command(label="Delete (Send to Trash)", command=self.delete_selected)
        
        self.tree.bind("<Double-1>", lambda e: self.open_selected())
        self.tree.bind("<Button-3>", self.show_context_menu)

        # Bottom frame for status and progress
        bottom_frame = ttk.Frame(self.root, padding=10)
        bottom_frame.pack(fill=tk.X)

        self.progress = ttk.Progressbar(bottom_frame, mode="indeterminate")
        self.progress.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 10))

        self.status_var = tk.StringVar(value="Ready.")
        ttk.Label(bottom_frame, textvariable=self.status_var, font=('Segoe UI', 9)).pack(side=tk.RIGHT)

    def browse(self):
        folder = filedialog.askdirectory(initialdir=self.current_path.get())
        if folder:
            self.current_path.set(folder)
            self.start_analysis()
            
    def go_up(self):
        current = self.current_path.get()
        parent = os.path.dirname(current)
        if parent and os.path.isdir(parent):
            self.current_path.set(parent)
            self.start_analysis()

    def show_context_menu(self, event):
        item = self.tree.identify_row(event.y)
        if item:
            self.tree.selection_set(item)
            self.menu.post(event.x_root, event.y_root)

    def start_analysis(self):
        target_dir = self.current_path.get()
        if not os.path.isdir(target_dir):
            messagebox.showerror("Error", "Invalid directory path.")
            return

        if self.is_scanning:
            self.cancel_scan = True
            self.status_var.set("Canceling previous scan...")
            return

        # Prepare UI for scan
        self.is_scanning = True
        self.cancel_scan = False
        self.btn_analyze.config(text="Cancel", bg="#f44336")
        self.status_var.set(f"Scanning {target_dir}... (This runs in the background)")
        self.progress.start(10)
        
        for item in self.tree.get_children():
            self.tree.delete(item)

        # Run in thread
        thread = threading.Thread(target=self._scan_thread, args=(target_dir,), daemon=True)
        thread.start()

    def _get_size_fast(self, path):
        """Fast size calculator using scandir iteratively instead of os.walk"""
        total = 0
        try:
            # using a stack for iterative traversal avoids recursion depth issues and is slightly faster
            dirs = [path]
            while dirs:
                if self.cancel_scan:
                    return 0
                current = dirs.pop()
                try:
                    for entry in os.scandir(current):
                        try:
                            if entry.is_dir(follow_symlinks=False):
                                dirs.append(entry.path)
                            else:
                                total += entry.stat(follow_symlinks=False).st_size
                        except (PermissionError, FileNotFoundError, OSError):
                            continue
                except (PermissionError, FileNotFoundError, OSError):
                    continue
        except Exception:
            pass
        return total

    def _scan_thread(self, target_dir):
        start_time = time.time()
        items = []
        
        try:
            for entry in os.scandir(target_dir):
                if self.cancel_scan:
                    break
                
                try:
                    is_dir = entry.is_dir(follow_symlinks=False)
                    item_type = "Folder" if is_dir else "File"
                    
                    if is_dir:
                        size = self._get_size_fast(entry.path)
                    else:
                        size = entry.stat(follow_symlinks=False).st_size
                        
                    items.append({
                        "name": entry.name,
                        "size": size,
                        "type": item_type,
                        "path": entry.path
                    })
                except (PermissionError, FileNotFoundError, OSError):
                    pass
        except PermissionError:
            self.root.after(0, lambda: messagebox.showerror("Error", "Permission denied to read this directory. Run as Administrator to scan C Drive!"))

        if not self.cancel_scan:
            items.sort(key=lambda x: x["size"], reverse=True)
            self.root.after(0, self._update_ui_post_scan, items, time.time() - start_time)
        else:
            self.root.after(0, self._scan_cancelled)

    def _update_ui_post_scan(self, items, elapsed):
        self.is_scanning = False
        self.progress.stop()
        self.btn_analyze.config(text="Analyze", bg="#4CAF50")
        
        for item in items:
            self.tree.insert("", tk.END, values=(
                item["name"], 
                format_size(item["size"]), 
                item["type"], 
                item["path"],
                item["size"] # hidden column for precise sorting
            ))
            
        self.status_var.set(f"Done in {elapsed:.1f}s. Found {len(items)} items.")

    def _scan_cancelled(self):
        self.is_scanning = False
        self.progress.stop()
        self.btn_analyze.config(text="Analyze", bg="#4CAF50")
        self.status_var.set("Scan cancelled.")

    def sort_tree(self, col, reverse):
        l = [(self.tree.set(k, col), k) for k in self.tree.get_children('')]
        
        if col == "size":
            # Sort by the hidden 5th column which has the raw integer bytes
            l = [(int(self.tree.item(k)["values"][4]), k) for k in self.tree.get_children('')]
        elif col == "type" or col == "name":
            l.sort(key=lambda t: t[0].lower(), reverse=reverse)
            
        if col == "size":
            l.sort(reverse=reverse)
            
        # rearrange items in sorted positions
        for index, (val, k) in enumerate(l):
            self.tree.move(k, '', index)
            
        # reverse sort next time
        self.tree.heading(col, command=lambda: self.sort_tree(col, not reverse))

    def open_selected(self):
        selected = self.tree.selection()
        if not selected: return
        path = self.tree.item(selected[0])["values"][3]
        if os.path.exists(path):
            self._launch_path(path)

    def open_selected_dir(self):
        selected = self.tree.selection()
        if not selected: return
        path = self.tree.item(selected[0])["values"][3]
        if os.path.exists(path):
            if os.path.isfile(path):
                path = os.path.dirname(path)
            self._launch_path(path)

    def _launch_path(self, path):
        try:
            if os.name == 'nt':
                os.startfile(path)
            else:
                subprocess.call(["xdg-open", path])
        except Exception as e:
            messagebox.showerror("Error", f"Failed to open: {e}")

    def delete_selected(self):
        selected = self.tree.selection()
        if not selected: return
        
        path = self.tree.item(selected[0])["values"][3]
        if messagebox.askyesno("Confirm Delete", f"Are you sure you want to permanently delete:\n{path}?"):
            try:
                if os.path.isfile(path):
                    os.remove(path)
                else:
                    shutil.rmtree(path)
                self.tree.delete(selected[0])
                messagebox.showinfo("Success", "Deleted successfully.")
            except Exception as e:
                messagebox.showerror("Error", f"Failed to delete: {e}\n(Might require Administrator privileges)")

if __name__ == "__main__":
    root = tk.Tk()
    app = StorageAnalyzerApp(root)
    root.mainloop()
