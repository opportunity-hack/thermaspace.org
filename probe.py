#!/usr/bin/env python3
"""Probe the FLIR One USB device: dump descriptors, interfaces, endpoints."""
import usb.core
import usb.util
import libusb_package

VID, PID = 0x09CB, 0x1996

backend = libusb_package.get_libusb1_backend()
dev = usb.core.find(idVendor=VID, idProduct=PID, backend=backend)
if dev is None:
    raise SystemExit("FLIR One not found (VID 0x09CB PID 0x1996)")

print(f"Device: {dev.idVendor:04x}:{dev.idProduct:04x}")
try:
    print(f"  Manufacturer: {usb.util.get_string(dev, dev.iManufacturer)}")
    print(f"  Product:      {usb.util.get_string(dev, dev.iProduct)}")
    print(f"  Serial:       {usb.util.get_string(dev, dev.iSerialNumber)}")
except Exception as e:
    print(f"  (string descriptors unavailable: {e})")

for cfg in dev:
    print(f"Configuration {cfg.bConfigurationValue}, interfaces={cfg.bNumInterfaces}")
    for intf in cfg:
        name = ""
        try:
            if intf.iInterface:
                name = usb.util.get_string(dev, intf.iInterface)
        except Exception:
            pass
        print(f"  Interface {intf.bInterfaceNumber} alt {intf.bAlternateSetting} "
              f"class {intf.bInterfaceClass:#04x}/{intf.bInterfaceSubClass:#04x} '{name}'")
        for ep in intf:
            direction = "IN " if ep.bEndpointAddress & 0x80 else "OUT"
            print(f"    EP {ep.bEndpointAddress:#04x} {direction} type={ep.bmAttributes & 3} "
                  f"maxpkt={ep.wMaxPacketSize}")
