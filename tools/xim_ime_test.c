/**
 * Minimal standalone X11/XIM test application for debugging IME integration.
 *
 * This program creates a small X11 window, sets up XIM/XIC exactly the way
 * SSOptimizer's native code does, and runs an event loop that logs every
 * XFilterEvent / Xutf8LookupString result to stdout.
 *
 * Build:
 *   cc -o xim_ime_test tools/xim_ime_test.c $(pkg-config --cflags --libs x11) -lX11
 *
 * Run:
 *   ./xim_ime_test
 *
 * Then type into the window.  Ctrl+Space should toggle fcitx on/off.
 * Each key event prints a detailed summary line.
 * Press Escape to quit.
 */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/keysym.h>
#include <locale.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Colour helpers for terminal output */
#define RESET   "\033[0m"
#define RED     "\033[31m"
#define GREEN   "\033[32m"
#define YELLOW  "\033[33m"
#define CYAN    "\033[36m"
#define BOLD    "\033[1m"

static void print_hex(const char *buf, int len) {
    for (int i = 0; i < len; i++) {
        printf("%02x ", (unsigned char)buf[i]);
    }
}

int main(void) {
    /* 1. Locale -----------------------------------------------------------*/
    setlocale(LC_ALL, "");
    printf(BOLD "XMODIFIERS" RESET " = %s\n",   getenv("XMODIFIERS") ?: "<unset>");
    printf(BOLD "LANG" RESET "       = %s\n",     getenv("LANG") ?: "<unset>");
    printf(BOLD "LC_CTYPE" RESET "   = %s\n",     getenv("LC_CTYPE") ?: "<unset>");
    printf(BOLD "GTK_IM_MODULE" RESET " = %s\n",  getenv("GTK_IM_MODULE") ?: "<unset>");
    printf(BOLD "QT_IM_MODULE" RESET "  = %s\n",  getenv("QT_IM_MODULE") ?: "<unset>");

    const char *applied = XSetLocaleModifiers("");
    printf("XSetLocaleModifiers(\"\") → %s\n", applied ? applied : "(null)");
    if (!XSupportsLocale()) {
        fprintf(stderr, RED "XSupportsLocale() = false — aborting\n" RESET);
        return 1;
    }
    printf("XSupportsLocale() = " GREEN "true" RESET "\n\n");

    /* 2. Open display & create window -----------------------------------*/
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) { fprintf(stderr, RED "Cannot open display\n" RESET); return 1; }

    int screen = DefaultScreen(dpy);
    Window win = XCreateSimpleWindow(dpy, RootWindow(dpy, screen),
                                     100, 100, 640, 200, 1,
                                     BlackPixel(dpy, screen),
                                     WhitePixel(dpy, screen));
    XStoreName(dpy, win, "SSOptimizer XIM/IME Test");
    XSelectInput(dpy, win, ExposureMask | KeyPressMask | KeyReleaseMask |
                           FocusChangeMask | StructureNotifyMask);
    XMapWindow(dpy, win);

    /* 3. Open XIM -------------------------------------------------------*/
    XIM xim = XOpenIM(dpy, NULL, NULL, NULL);
    if (!xim) {
        /* Retry with @im= fallback */
        XSetLocaleModifiers("@im=");
        xim = XOpenIM(dpy, NULL, NULL, NULL);
    }
    if (!xim) {
        fprintf(stderr, RED "XOpenIM failed\n" RESET);
        XDestroyWindow(dpy, win);
        XCloseDisplay(dpy);
        return 1;
    }
    printf(GREEN "XOpenIM succeeded" RESET "\n");

    /* 4. Query & print supported styles --------------------------------*/
    XIMStyles *styles = NULL;
    XGetIMValues(xim, XNQueryInputStyle, &styles, NULL);
    if (styles) {
        printf("Supported input styles (%d):\n", styles->count_styles);
        for (int i = 0; i < styles->count_styles; i++) {
            printf("  [%d] 0x%lx\n", i, styles->supported_styles[i]);
        }
    }

    /* 5. Create XIC — try several styles --------------------------------*/
    XIMStyle chosen_style = XIMPreeditNothing | XIMStatusNothing;  /* 0x408 */
    XIC xic = XCreateIC(xim,
                         XNInputStyle, chosen_style,
                         XNClientWindow, win,
                         XNFocusWindow, win,
                         NULL);
    if (!xic) {
        /* fallback: XIMPreeditNone | XIMStatusNothing */
        chosen_style = XIMPreeditNone | XIMStatusNothing;
        xic = XCreateIC(xim,
                         XNInputStyle, chosen_style,
                         XNClientWindow, win,
                         XNFocusWindow, win,
                         NULL);
    }
    if (!xic) {
        fprintf(stderr, RED "XCreateIC failed for all styles\n" RESET);
        XCloseIM(xim);
        XDestroyWindow(dpy, win);
        XCloseDisplay(dpy);
        return 1;
    }
    printf(GREEN "XCreateIC succeeded" RESET " (style=0x%lx)\n", chosen_style);

    /* 6. Install XIC filter events on the window -----------------------*/
    long filter_mask = 0;
    XGetICValues(xic, XNFilterEvents, &filter_mask, NULL);
    printf("XIC filter event mask: 0x%lx\n", filter_mask);

    XWindowAttributes attr;
    XGetWindowAttributes(dpy, win, &attr);
    long combined_mask = attr.your_event_mask | filter_mask;
    XSelectInput(dpy, win, combined_mask);

    /* 7. Set IC focus ---------------------------------------------------*/
    XSetICFocus(xic);
    printf(GREEN "XSetICFocus done" RESET "\n");
    printf("\n" BOLD "=== Event loop started — type to test IME ===" RESET "\n");
    printf("   Press " BOLD "Ctrl+Space" RESET " to toggle input method\n");
    printf("   Press " BOLD "Escape" RESET " to quit\n\n");

    /* 8. Collected committed text buffer --------------------------------*/
    char committed_line[1024];
    int committed_pos = 0;

    /* 9. Event loop -----------------------------------------------------*/
    XEvent ev;
    int running = 1;
    int event_no = 0;

    while (running) {
        XNextEvent(dpy, &ev);
        event_no++;

        /* Show ALL event types that go through XFilterEvent */
        int filtered = XFilterEvent(&ev, None);

        if (ev.type == KeyPress || ev.type == KeyRelease) {
            const char *type_str = (ev.type == KeyPress) ? "KeyPress" : "KeyRelease";
            XKeyEvent *kev = &ev.xkey;

            printf(CYAN "[%04d]" RESET " %-10s keycode=%-3u state=0x%04x "
                   "send_event=%d  XFilterEvent=" ,
                   event_no, type_str, kev->keycode, kev->state, kev->send_event);

            if (filtered) {
                printf(YELLOW "True" RESET " (IM composing)\n");
            } else {
                printf(GREEN "False" RESET);

                if (ev.type == KeyPress) {
                    char buf[256] = {0};
                    KeySym ks = 0;
                    Status status = 0;
                    int len = Xutf8LookupString(xic, kev, buf, sizeof(buf) - 1, &ks, &status);

                    const char *status_name = "?";
                    switch (status) {
                        case XLookupNone:    status_name = "XLookupNone"; break;
                        case XLookupKeySym:  status_name = "XLookupKeySym"; break;
                        case XLookupChars:   status_name = "XLookupChars"; break;
                        case XLookupBoth:    status_name = "XLookupBoth"; break;
                        case XBufferOverflow:status_name = "XBufferOverflow"; break;
                    }

                    printf("  → Xutf8Lookup: status=%s keysym=0x%lx len=%d",
                           status_name, ks, len);
                    if (len > 0) {
                        buf[len] = '\0';
                        printf(" text=\"" GREEN "%s" RESET "\"", buf);
                        printf(" hex=[");
                        print_hex(buf, len);
                        printf("]");

                        /* Append to committed line */
                        if (committed_pos + len < (int)sizeof(committed_line) - 1) {
                            memcpy(committed_line + committed_pos, buf, len);
                            committed_pos += len;
                            committed_line[committed_pos] = '\0';
                        }

                        /* Check for Escape */
                        if (ks == XK_Escape) {
                            running = 0;
                        }
                    }
                    printf("\n");

                    /* If keysym is Escape even without commit, quit */
                    if (ks == XK_Escape) running = 0;
                } else {
                    printf("\n");
                }
            }
        } else if (ev.type == FocusIn) {
            printf(CYAN "[%04d]" RESET " FocusIn   XFilterEvent=%s\n",
                   event_no, filtered ? "True" : "False");
            if (!filtered) {
                XSetICFocus(xic);
                printf("       → XSetICFocus\n");
            }
        } else if (ev.type == FocusOut) {
            printf(CYAN "[%04d]" RESET " FocusOut  XFilterEvent=%s\n",
                   event_no, filtered ? "True" : "False");
            if (!filtered) {
                XUnsetICFocus(xic);
                printf("       → XUnsetICFocus\n");
            }
        } else {
            /* Other events: ClientMessage, etc. */
            printf(CYAN "[%04d]" RESET " type=%-3d  XFilterEvent=%s\n",
                   event_no, ev.type, filtered ? "True" : "False");
        }

        fflush(stdout);
    }

    /* 10. Print collected text ------------------------------------------*/
    committed_line[committed_pos] = '\0';
    printf("\n" BOLD "=== Committed text collected ===" RESET "\n");
    printf("  \"%s\"\n", committed_line);
    printf("  hex=[");
    print_hex(committed_line, committed_pos);
    printf("]\n\n");

    /* 11. Cleanup -------------------------------------------------------*/
    XUnsetICFocus(xic);
    XDestroyIC(xic);
    XCloseIM(xim);
    XDestroyWindow(dpy, win);
    XCloseDisplay(dpy);
    printf("Clean shutdown.\n");
    return 0;
}
