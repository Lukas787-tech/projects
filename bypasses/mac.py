import subprocess
import re
import random
import winreg
from contextlib import suppress

def generate_random_mac():
    """Generate a valid random MAC address"""
    return "02:" + ":".join(f"{random.randint(0, 255):02x}" for _ in range(5))

def get_network_adapters():
    """List network adapters"""
    result = subprocess.check_output("getmac /v", shell=True).decode()
    print("Available adapters:\n")
    print(result)
    return result

def change_mac_windows(new_mac=None):
    if not new_mac:
        new_mac = generate_random_mac()
    
    print(f"[+] Setting new MAC: {new_mac}")
    
    # Registry path for network adapters
    reg_path = r"SYSTEM\CurrentControlSet\Control\Class\{4D36E972-E325-11CE-BFC1-08002BE10318}"
    
    try:
        with winreg.ConnectRegistry(None, winreg.HKEY_LOCAL_MACHINE) as hkey:
            i = 0
            while True:
                try:
                    subkey_path = fr"{reg_path}\{i:04d}"
                    with winreg.OpenKey(hkey, subkey_path, 0, winreg.KEY_ALL_ACCESS) as subkey:
                        # Check if this is a network adapter
                        try:
                            winreg.QueryValueEx(subkey, "DriverDesc")
                            # Set new MAC
                            winreg.SetValueEx(subkey, "NetworkAddress", 0, winreg.REG_SZ, new_mac.replace(":", ""))
                            print(f"[+] Updated adapter {i}")
                        except FileNotFoundError:
                            pass
                    i += 1
                except FileNotFoundError:
                    break
    except Exception as e:
        print("[-] Registry error:", e)
    
    # Restart adapter (replace "Wi-Fi" with your adapter name)
    adapter_name = "Ethernet"  # Changed from Wi-Fi based on getmac output
    subprocess.run(f"netsh interface set interface \"{adapter_name}\" admin=disabled", shell=True)
    subprocess.run(f"netsh interface set interface \"{adapter_name}\" admin=enabled", shell=True)
    
    print("[+] MAC changed! Reconnect to the network.")

if __name__ == "__main__":
    get_network_adapters()
    change_mac_windows()
