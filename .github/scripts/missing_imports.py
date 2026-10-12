"""List the DLL imports of Windows executables that this machine can't resolve.

Diagnoses STATUS_ENTRYPOINT_NOT_FOUND (0xc0000139) when a test binary won't
start. Usage: python missing_imports.py "target/debug/deps/name-*.exe" ...
"""

import ctypes
import glob
import sys

import pefile

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
kernel32.LoadLibraryExW.restype = ctypes.c_void_p
kernel32.LoadLibraryExW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p, ctypes.c_uint32]
kernel32.GetProcAddress.restype = ctypes.c_void_p
kernel32.GetProcAddress.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

for pattern in sys.argv[1:]:
    for exe in glob.glob(pattern):
        print(exe)
        pe = pefile.PE(exe, fast_load=True)
        pe.parse_data_directories(
            directories=[
                pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
                pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"],
            ]
        )
        missing = 0
        entries = getattr(pe, "DIRECTORY_ENTRY_IMPORT", []) + getattr(pe, "DIRECTORY_ENTRY_DELAY_IMPORT", [])
        for entry in entries:
            dll = entry.dll.decode()
            handle = kernel32.LoadLibraryExW(dll, None, 0)
            if not handle:
                print(f"  cannot load {dll} (error {ctypes.get_last_error()})")
                missing += 1
                continue
            for imp in entry.imports:
                if imp.name:
                    proc = kernel32.GetProcAddress(handle, ctypes.c_char_p(imp.name))
                    label = imp.name.decode()
                else:
                    proc = kernel32.GetProcAddress(handle, ctypes.c_void_p(imp.ordinal))
                    label = f"ordinal {imp.ordinal}"
                if not proc:
                    print(f"  {dll}: {label} not found")
                    missing += 1
        print(f"  {missing} unresolved import(s)")
