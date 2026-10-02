#!/usr/bin/env python3
"""Outil protocole MCU Jancar (UJC201 / AC8257, firmware JCST_AC8257_8T7).

Trame : EE FA LEN CMD D0..Dn-1 CS   (LEN = n + 1, CS = somme 8 bits des octets precedents).
Reference : docs/mcu_firmware.md, section 6.

  jacmcu.py frame F0 0B 00                 construit une trame
  jacmcu.py decode "ee fa 02 04 01 ef"     decode un flux hexa (une ou plusieurs trames)
  jacmcu.py log /tmp/mcu.log               decode le journal de touchfix (lignes "rx/tx/bad ee fa ..")
  jacmcu.py monitor /dev/ttyS1 [--quiet]   PC_READY + requetes F0, puis affiche les trames (Linux/TWRP)
  jacmcu.py send /dev/ttyS1 F0 0A 00       envoie une trame et affiche les reponses 1 s

Ne pas utiliser sous Android : ivi-services occupe deja /dev/ttyS1 (passer par l'AIDL ICar).
Les commandes 01 / 0E / 80 / F1 (extinction, reset SoC, bootloader, veille) sont refusees sans --force.
"""
import os
import sys
import time

DANGEROUS = {0x01: "extinction differee", 0x0E: "reset du SoC", 0x80: "passage au bootloader",
             0xF1: "duree de veille"}

TX_NAMES = {0x01: "POWER", 0x08: "MUTE", 0x09: "SET_RTC", 0x0E: "RESET_SOC", 0x0F: "CONFIG", 0x10: "CAN_TX",
            0x11: "LEARN_WHEEL", 0x1F: "PC_READY", 0x21: "LEARN_PANEL", 0x31: "IR(ignore)", 0x33: "WHEEL_SCALE(ignore)",
            0x43: "RADIO_ANT", 0x44: "AMP_REM", 0x45: "ROTATE(ignore)", 0x80: "BOOTLOADER", 0xF0: "QUERY",
            0xF1: "SLEEP_TIME"}
RX_NAMES = {0x00: "ACC", 0x04: "HANDBRAKE", 0x08: "MUTE", 0x09: "RTC", 0x0A: "VERSION", 0x0B: "ILL",
            0x0D: "BACKLIGHT", 0x0F: "CONFIG", 0x10: "CAN_RX", 0x20: "KEY", 0x30: "KEY_LEARN", 0x43: "RADIO_ANT",
            0xC0: "ACK"}
QUERIES = {0x00: "ACC", 0x04: "frein a main", 0x08: "mute", 0x09: "date+heure", 0x0A: "version", 0x0B: "feux/ILL",
           0x0D: "retroeclairage", 0x0F: "option +0x11", 0x43: "antenne radio"}
CFG_SUB = {0x00: "baud USART1", 0x02: "PWM CH1 %", 0x03: "PWM CH2 %", 0x04: "LED facade", 0x06: "molette",
           0x07: "option +0x11", 0x08: "type facade", 0x0A: "seuil haut", 0x0B: "seuil bas"}
BAUDS = [9600, 19200, 38400, 57600, 115200, 230400, 460800]
HIGH_V = ["16", "16.5", "17", "18", "19", "20"]
LOW_V = ["9", "9.5", "10"]


def frame(cmd, data=b""):
    data = bytes(data)
    if len(data) > 126:
        raise ValueError("donnees > 126 octets (Android plante sur LEN >= 0x80)")
    f = bytes([0xEE, 0xFA, len(data) + 1, cmd]) + data
    return f + bytes([sum(f) & 0xFF])


def parse(buf):
    """Decoupe un flux : renvoie (liste de (trame, ok_checksum), octets hors trame, reste)."""
    frames, junk, i = [], bytearray(), 0
    while i < len(buf):
        if buf[i] != 0xEE or (i + 1 < len(buf) and buf[i + 1] != 0xFA):
            junk.append(buf[i]); i += 1; continue
        if i + 3 > len(buf):
            break
        ln = buf[i + 2]
        end = i + 3 + ln + 1
        if ln == 0 or ln > 131:
            junk.append(buf[i]); i += 1; continue
        if end > len(buf):
            break
        f = bytes(buf[i:end])
        frames.append((f, sum(f[:-1]) & 0xFF == f[-1]))
        i = end
    return frames, bytes(junk), bytes(buf[i:])


def describe(f, direction=None):
    """direction : 'tx' (SoC->MCU), 'rx' (MCU->SoC) ou None (devine)."""
    cmd, d = f[3], f[4:-1]
    if direction is None:
        direction = "rx" if cmd not in TX_NAMES else "tx" if cmd not in RX_NAMES else "?"
    names = TX_NAMES if direction == "tx" else RX_NAMES if direction == "rx" else {**RX_NAMES, **TX_NAMES}
    name = names.get(cmd, "CMD_%02X" % cmd)
    info = ""
    if cmd == 0xF0 and d:
        info = "requete %s" % QUERIES.get(d[0], "%02X" % d[0])
    elif cmd == 0x0F and d and direction != "rx":
        sub, a = d[0], d[1:]
        info = CFG_SUB.get(sub, "sous-cmd %02X (ignoree)" % sub)
        if sub == 0x00 and a and a[0] < len(BAUDS): info += " = %d" % BAUDS[a[0]]
        elif sub == 0x0A and a and a[0] < len(HIGH_V): info += " = %s V" % HIGH_V[a[0]]
        elif sub == 0x0B and a and a[0] < len(LOW_V): info += " = %s V" % LOW_V[a[0]]
        elif sub == 0x04 and len(a) >= 5: info += " type=%d R=%d G=%d B=%d mode=%d" % tuple(a[:5])
        elif a: info += " = %s" % " ".join(str(x) for x in a)
    elif cmd == 0x0A and direction != "tx" and len(d) > 2:
        info = repr(d.decode("ascii", "replace").rstrip())
    elif cmd == 0x09 and d:
        if d[0] == 0 and len(d) >= 5: info = "date %02d%02d-%02d-%02d" % (d[1], d[2], d[3], d[4])
        elif d[0] == 1 and len(d) >= 4: info = "heure %02d:%02d:%02d" % (d[1], d[2], d[3])
    elif cmd in (0x20, 0x30) and len(d) >= 5:
        info = "relache" if d[2:5] == b"\xff\xff\xff" else "canal %d adc %02X %02X %02X" % (d[0], d[2], d[3], d[4])
    elif cmd == 0xC0 and d:
        info = "ack de %02X" % d[0]
    elif cmd == 0x10:
        info = "%d octets boitier CAN" % len(d)
    elif cmd == 0xF1 and d:
        info = "%d x 420 min" % d[0]
    elif len(d) == 1:
        info = str(d[0])
    return "%-11s %s" % (name, info)


def hexs(b):
    return " ".join("%02X" % x for x in b)


def show(f, ok, direction=None, prefix=""):
    print("%s%-40s %s%s" % (prefix, hexs(f), describe(f, direction), "" if ok else "  [CHECKSUM KO]"))


def parse_hex(args):
    toks = " ".join(args).replace(",", " ").replace(":", " ").split()
    out = bytearray()
    for t in toks:
        t = t[2:] if t.lower().startswith("0x") else t
        out += bytes.fromhex(t if len(t) % 2 == 0 else "0" + t)
    return bytes(out)


def check_cmd(cmd, force):
    if cmd in DANGEROUS and not force:
        sys.exit("refuse : %02X = %s (ajouter --force si vous savez ce que vous faites)" % (cmd, DANGEROUS[cmd]))


def open_tty(path):
    import termios
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    a = termios.tcgetattr(fd)
    a[0] = 0; a[1] = 0; a[3] = 0
    a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    a[4] = a[5] = termios.B115200
    a[6][termios.VMIN] = 0; a[6][termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, a)
    termios.tcflush(fd, termios.TCIOFLUSH)
    return fd


def tx(fd, f):
    os.write(fd, f)
    show(f, True, "tx", "> ")


def rx_loop(fd, duration=None, quiet=False):
    import select
    buf, t0 = b"", time.time()
    while duration is None or time.time() - t0 < duration:
        r, _, _ = select.select([fd], [], [], 0.2)
        if not r:
            continue
        try:
            buf += os.read(fd, 512)
        except BlockingIOError:
            continue
        frames, junk, buf = parse(buf)
        if junk:
            print("< (hors trame) %s" % hexs(junk))
        for f, ok in frames:
            if quiet and f[3] == 0xC0:
                continue
            show(f, ok, "rx", "< ")
        sys.stdout.flush()


def main(argv):
    force = "--force" in argv
    quiet = "--quiet" in argv
    argv = [a for a in argv if a not in ("--force", "--quiet")]
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__); return 0
    op, args = argv[1], argv[2:]
    if op == "frame":
        b = parse_hex(args)
        check_cmd(b[0], True)
        print(hexs(frame(b[0], b[1:])))
    elif op == "decode":
        frames, junk, rest = parse(parse_hex(args))
        for f, ok in frames:
            show(f, ok)
        if junk: print("hors trame : %s" % hexs(junk))
        if rest: print("incomplet : %s" % hexs(rest))
    elif op == "log":
        for line in open(args[0] if args else "/tmp/mcu.log", errors="replace"):
            p = line.split()
            if len(p) < 2 or p[0] not in ("rx", "tx", "bad"):
                continue
            b = bytes(int(x, 16) for x in p[1:])
            if p[0] == "bad":
                print("! %s  [CHECKSUM KO]" % hexs(b)); continue
            frames, junk, rest = parse(b)
            for f, ok in frames:
                show(f, ok, p[0], "> " if p[0] == "tx" else "< ")
            if junk or rest: print("  (hors trame) %s" % hexs(junk + rest))
    elif op == "monitor":
        fd = open_tty(args[0] if args else "/dev/ttyS1")
        tx(fd, frame(0x1F, b"\x01"))
        for q in (0x00, 0x04, 0x0B, 0x0D, 0x43):
            time.sleep(0.05); tx(fd, frame(0xF0, bytes([q, 0])))
        try:
            rx_loop(fd, None, quiet)
        except KeyboardInterrupt:
            pass
    elif op == "send":
        if len(args) < 2:
            sys.exit("usage : jacmcu.py send /dev/ttyS1 CMD [donnees..]")
        b = parse_hex(args[1:])
        check_cmd(b[0], force)
        fd = open_tty(args[0])
        tx(fd, frame(b[0], b[1:]))
        rx_loop(fd, 1.0, quiet)
    else:
        print(__doc__); return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
