#!/usr/bin/env python3
"""View the FLIR One thermal stream served by the Quest 3 app on this Mac.

Usage:  python mac_viewer.py <quest-ip> [port]
(or just open http://<quest-ip>:8080 in a browser)
"""
import sys

import cv2

ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.100"
port = sys.argv[2] if len(sys.argv) > 2 else "8080"
url = f"http://{ip}:{port}/stream"

cap = cv2.VideoCapture(url)
if not cap.isOpened():
    raise SystemExit(f"cannot open {url} — is the Quest app running and on the same Wi-Fi?")
print(f"streaming from {url} — press q to quit")
while True:
    ok, frame = cap.read()
    if not ok:
        print("stream ended / dropped")
        break
    cv2.imshow("FLIR One via Quest 3", frame)
    if cv2.waitKey(1) & 0xFF == ord("q"):
        break
cap.release()
cv2.destroyAllWindows()
