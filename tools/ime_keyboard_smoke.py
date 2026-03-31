#!/usr/bin/env python3
"""Send scripted keyboard input to a Starsector window over X11.

This helper is intentionally small and dependency-light: it uses python-xlib's
XTEST extension to focus a matching window and synthesize key presses.

Typical usage:
  python3 tools/ime_keyboard_smoke.py \
      --window-regex '(?i)(starsector|starfarer)' \
      --sequence 'ctrl+space;wait=0.25;type=sr;wait=0.25;key=BackSpace'

The default sequence is meant for IME smoke testing:
  1) toggle with Ctrl+Space
  2) type "sr"
  3) press BackSpace

If the target window cannot be found, the script prints a short list of visible
window titles to help you adjust the regex.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence, Tuple

from Xlib import X, XK, display, error
from Xlib.ext import xtest


MODIFIER_KEYSYMS = {
    "ctrl": "Control_L",
    "control": "Control_L",
    "shift": "Shift_L",
    "alt": "Alt_L",
    "meta": "Meta_L",
    "super": "Super_L",
}

SPECIAL_KEYSYMS = {
    "backspace": "BackSpace",
    "enter": "Return",
    "return": "Return",
    "space": "space",
    "tab": "Tab",
    "escape": "Escape",
    "esc": "Escape",
    "delete": "Delete",
    "del": "Delete",
    "left": "Left",
    "right": "Right",
    "up": "Up",
    "down": "Down",
}


@dataclass(frozen=True)
class WindowMatch:
    window: object
    title: str
    wm_class: str


@dataclass(frozen=True)
class Action:
    kind: str
    value: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--window-regex",
        default=r"(?i)(starsector|starfarer)",
        help="Regex used to locate the target top-level window title/class.",
    )
    parser.add_argument(
        "--sequence",
        default="ctrl+space;wait=0.25;type=sr;wait=0.25;key=backspace",
        help="Semicolon-separated action list: ctrl+space, type=abc, key=backspace, wait=0.25",
    )
    parser.add_argument(
        "--wait-timeout",
        type=float,
        default=45.0,
        help="Seconds to wait for a matching window to appear.",
    )
    parser.add_argument(
        "--focus-delay",
        type=float,
        default=0.25,
        help="Seconds to wait after focusing the target window.",
    )
    parser.add_argument(
        "--action-delay",
        type=float,
        default=0.05,
        help="Seconds to wait between key presses.",
    )
    parser.add_argument(
        "--dump-windows",
        action="store_true",
        help="Print the visible window tree and exit without sending input.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    disp = display.Display()
    root = disp.screen().root
    pattern = re.compile(args.window_regex)

    if args.dump_windows:
        dump_window_tree(root)
        return 0

    match = wait_for_window(root, pattern, args.wait_timeout)
    if match is None:
        print(
            f"[ime-smoke] ERROR: no window matched regex {args.window_regex!r} within {args.wait_timeout:.1f}s",
            file=sys.stderr,
        )
        print("[ime-smoke] Visible windows:", file=sys.stderr)
        dump_window_tree(root, limit=30, indent="  ", file=sys.stderr)
        return 2

    print(f"[ime-smoke] target title={match.title!r} class={match.wm_class!r}")
    focus_window(disp, match.window)
    time.sleep(args.focus_delay)

    actions = parse_sequence(args.sequence)
    run_actions(disp, actions, action_delay=args.action_delay)
    print("[ime-smoke] sequence sent")
    return 0


def wait_for_window(root, pattern: re.Pattern[str], timeout: float) -> Optional[WindowMatch]:
    deadline = time.monotonic() + timeout
    last_seen: List[WindowMatch] = []
    while time.monotonic() < deadline:
        last_seen = find_matching_windows(root, pattern)
        if last_seen:
            # Prefer the first exact match, otherwise the first visible hit.
            return last_seen[0]
        time.sleep(0.25)
    return None


def find_matching_windows(root, pattern: re.Pattern[str]) -> List[WindowMatch]:
    matches: List[WindowMatch] = []
    for window in walk_windows(root):
        title, wm_class = window_identity(window)
        if not title and not wm_class:
            continue
        if pattern.search(title) or pattern.search(wm_class):
            matches.append(WindowMatch(window=window, title=title, wm_class=wm_class))
    return matches


def walk_windows(root) -> Iterable[object]:
    stack = [root]
    seen = set()
    while stack:
        window = stack.pop()
        if window.id in seen:
            continue
        seen.add(window.id)
        yield window
        try:
            children = window.query_tree().children
        except error.BadWindow:
            continue
        for child in children:
            stack.append(child)


def window_identity(window) -> Tuple[str, str]:
    title = ""
    wm_class = ""
    try:
        title = window.get_wm_name() or ""
    except error.BadWindow:
        pass
    try:
        klass = window.get_wm_class() or ()
        wm_class = " ".join(klass)
    except error.BadWindow:
        pass
    return title, wm_class


def dump_window_tree(root, limit: int = 200, indent: str = "", file=sys.stdout) -> None:
    count = 0
    for window in walk_windows(root):
        title, wm_class = window_identity(window)
        if not title and not wm_class:
            continue
        print(f"{indent}- id=0x{window.id:x} title={title!r} class={wm_class!r}", file=file)
        count += 1
        if count >= limit:
            print(f"{indent}... truncated after {limit} windows ...", file=file)
            break


def focus_window(disp, window) -> None:
    try:
        window.configure(stack_mode=X.Above)
    except error.BadWindow:
        pass
    try:
        window.set_input_focus(X.RevertToParent, X.CurrentTime)
    except error.BadWindow:
        pass
    try:
        disp.sync()
    except error.XError:
        pass


def parse_sequence(sequence: str) -> List[Action]:
    actions: List[Action] = []
    for chunk in sequence.split(";"):
        token = chunk.strip()
        if not token:
            continue
        if token.lower().startswith("wait="):
            actions.append(Action("wait", token.split("=", 1)[1].strip()))
        elif token.lower().startswith("type="):
            actions.append(Action("type", token.split("=", 1)[1]))
        elif token.lower().startswith("key="):
            actions.append(Action("key", token.split("=", 1)[1].strip()))
        elif token.lower().startswith("click="):
            actions.append(Action("click", token.split("=", 1)[1].strip()))
        else:
            actions.append(Action("combo", token))
    return actions


def run_actions(disp, actions: Sequence[Action], action_delay: float) -> None:
    for action in actions:
        if action.kind == "wait":
            time.sleep(float(action.value))
            continue
        if action.kind == "type":
            type_text(disp, action.value, action_delay)
            continue
        if action.kind == "key":
            tap_named_key(disp, action.value)
            time.sleep(action_delay)
            continue
        if action.kind == "combo":
            tap_combo(disp, action.value)
            time.sleep(action_delay)
            continue
        if action.kind == "click":
            click_at(disp, action.value)
            time.sleep(action_delay)
            continue
        raise ValueError(f"Unknown action kind: {action.kind}")

    try:
        disp.sync()
    except error.XError:
        pass


def click_at(disp, coords: str) -> None:
    """Click at the given X,Y coordinates relative to the root window."""
    parts = coords.split(",")
    if len(parts) != 2:
        raise ValueError(f"click= expects X,Y coordinates, got: {coords!r}")
    x, y = int(parts[0].strip()), int(parts[1].strip())
    root = disp.screen().root
    root.warp_pointer(x, y)
    disp.sync()
    time.sleep(0.05)
    xtest.fake_input(disp, X.ButtonPress, 1)
    disp.sync()
    time.sleep(0.05)
    xtest.fake_input(disp, X.ButtonRelease, 1)
    disp.sync()


def tap_combo(disp, combo: str) -> None:
    parts = [part.strip() for part in combo.split("+") if part.strip()]
    if not parts:
        return

    modifiers = parts[:-1]
    key_name = parts[-1]
    modifier_keycodes = [keycode_for_modifier(disp, modifier) for modifier in modifiers]
    keycode = keycode_for_keyname(disp, key_name)

    for modifier_keycode in modifier_keycodes:
        xtest.fake_input(disp, X.KeyPress, modifier_keycode)
    xtest.fake_input(disp, X.KeyPress, keycode)
    xtest.fake_input(disp, X.KeyRelease, keycode)
    for modifier_keycode in reversed(modifier_keycodes):
        xtest.fake_input(disp, X.KeyRelease, modifier_keycode)
    disp.sync()


def tap_named_key(disp, name: str) -> None:
    keycode = keycode_for_keyname(disp, name)
    xtest.fake_input(disp, X.KeyPress, keycode)
    xtest.fake_input(disp, X.KeyRelease, keycode)
    disp.sync()


def type_text(disp, text: str, action_delay: float) -> None:
    for char in text:
        if char == "\n":
            tap_named_key(disp, "Return")
        elif char == "\t":
            tap_named_key(disp, "Tab")
        else:
            keycode = keycode_for_character(disp, char)
            xtest.fake_input(disp, X.KeyPress, keycode)
            xtest.fake_input(disp, X.KeyRelease, keycode)
            disp.sync()
        time.sleep(action_delay)


def keycode_for_modifier(disp, modifier: str) -> int:
    keysym_name = MODIFIER_KEYSYMS.get(modifier.lower())
    if keysym_name is None:
        raise ValueError(f"Unsupported modifier in combo: {modifier!r}")
    return keycode_for_keyname(disp, keysym_name)


def keycode_for_keyname(disp, name: str) -> int:
    normalized = SPECIAL_KEYSYMS.get(name.lower(), name)
    keysym = XK.string_to_keysym(normalized)
    if keysym == 0:
        raise ValueError(f"Unknown key name: {name!r}")
    keycode = disp.keysym_to_keycode(keysym)
    if keycode == 0:
        raise ValueError(f"Key symbol has no mapped keycode: {name!r}")
    return keycode


def keycode_for_character(disp, char: str) -> int:
    if len(char) != 1:
        raise ValueError(f"Expected a single character, got: {char!r}")
    keysym = XK.string_to_keysym(char)
    if keysym == 0:
        raise ValueError(f"Cannot synthesize character: {char!r}")
    keycode = disp.keysym_to_keycode(keysym)
    if keycode == 0:
        raise ValueError(f"Character has no mapped keycode: {char!r}")
    return keycode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
