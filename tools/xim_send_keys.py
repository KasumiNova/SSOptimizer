#!/usr/bin/env python3
"""
Send key events to the XIM test window using python-xlib XSendEvent.
Usage: python3 tools/xim_send_keys.py <window_id_hex_or_dec> <keys>
Example: python3 tools/xim_send_keys.py 0x2400002 "hello"
"""
import sys
import time
from Xlib import X, display, XK, protocol

def send_key(d, win, keycode, state=0):
    """Send a KeyPress + KeyRelease to the window."""
    root = d.screen().root
    
    # KeyPress
    ev = protocol.event.KeyPress(
        time=X.CurrentTime,
        root=root,
        window=win,
        child=X.NONE,
        root_x=0, root_y=0,
        event_x=10, event_y=10,
        state=state,
        detail=keycode,
        same_screen=True,
    )
    win.send_event(ev, event_mask=X.KeyPressMask)
    d.sync()
    time.sleep(0.05)
    
    # KeyRelease
    ev = protocol.event.KeyRelease(
        time=X.CurrentTime,
        root=root,
        window=win,
        child=X.NONE,
        root_x=0, root_y=0,
        event_x=10, event_y=10,
        state=state,
        detail=keycode,
        same_screen=True,
    )
    win.send_event(ev, event_mask=X.KeyReleaseMask)
    d.sync()

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <window_id> <keys>")
        print("  keys can be: 'hello' for character keys, 'ctrl+space' for modifier combos")
        sys.exit(1)
    
    wid = int(sys.argv[1], 0)  # auto-detect hex/dec
    keys = sys.argv[2]
    
    d = display.Display()
    win = d.create_resource_object('window', wid)
    
    print(f"Sending keys to window 0x{wid:x}: {keys!r}")
    
    for ch in keys:
        keysym = XK.string_to_keysym(ch)
        if keysym == 0:
            print(f"  Unknown keysym for '{ch}', skipping")
            continue
        keycode = d.keysym_to_keycode(keysym)
        if keycode == 0:
            print(f"  No keycode for keysym 0x{keysym:x} ('{ch}'), skipping")
            continue
        print(f"  '{ch}' → keysym=0x{keysym:x} keycode={keycode}")
        send_key(d, win, keycode)
        time.sleep(0.1)
    
    d.close()
    print("Done.")

if __name__ == "__main__":
    main()
