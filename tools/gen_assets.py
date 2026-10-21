"""Generates PakPak's original binary assets:
  - Android launcher icons (legacy + adaptive), iOS app icon + launch screen, drawn from pixel grids
  - chiptune sound effects and two music loops, synthesised from scratch

Usage: python tools/gen_assets.py   (needs Pillow)
"""
import math
import os
import random
import struct
import wave

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'mobile', 'androidApp', 'src', 'main', 'res')
AUDIO = os.path.join(ROOT, 'mobile', 'shared', 'src', 'commonMain', 'assets', 'audio')
XCASSETS = os.path.join(ROOT, 'mobile', 'iosApp', 'iosApp', 'Assets.xcassets')

# Same bird as gfx/Sprites.kt (o outline, b body, w wing, l belly, e eye, p pupil, k beak).
BIRD = [
    "......ooooo......",
    "....oobbbbboo....",
    "...obbbbbboeeo...",
    "..obbbbbbboepeo..",
    ".oooobbbbboeeeo..",
    "owwwwobbbbbooooo.",
    "owwwwwobbbokkkkko",
    "owwwwobbbbooooo..",
    ".oooolllllokkko..",
    "..olllllllloooo..",
    "...ooolllllo.....",
    "......ooooo......",
]
COLORS = {
    'o': (58, 42, 0), 'b': (255, 210, 63), 'w': (255, 242, 168), 'l': (255, 242, 168),
    'e': (255, 255, 255), 'p': (20, 20, 30), 'k': (255, 138, 31),
}
COLORS['w'] = (255, 170, 40)
SKY_TOP, SKY_BOTTOM = (94, 200, 242), (180, 236, 255)


def draw_grid(img, grid, ox, oy, scale):
    d = ImageDraw.Draw(img)
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            if ch in COLORS:
                d.rectangle([ox + x * scale, oy + y * scale, ox + (x + 1) * scale - 1, oy + (y + 1) * scale - 1], fill=COLORS[ch] + (255,))


def icon_grid(n=24):
    """24x24 pixel icon: sky, a cloud, a bamboo pole and the bird."""
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(n):
        t = y / (n - 1)
        c = tuple(int(SKY_TOP[i] + (SKY_BOTTOM[i] - SKY_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (n - 1, y)], fill=c + (255,))
    d.rectangle([2, 4, 7, 5], fill=(255, 255, 255, 255))
    d.rectangle([3, 3, 6, 3], fill=(255, 255, 255, 255))
    # bamboo pole bottom right
    d.rectangle([18, 15, 22, 23], fill=(76, 175, 80, 255))
    d.rectangle([18, 15, 18, 23], fill=(46, 125, 50, 255))
    d.rectangle([17, 14, 23, 15], fill=(46, 125, 50, 255))
    d.rectangle([18, 19, 22, 19], fill=(46, 125, 50, 255))
    # grass
    d.rectangle([0, 21, n - 1, 23], fill=(124, 200, 72, 255))
    d.rectangle([0, 21, n - 1, 21], fill=(84, 160, 52, 255))
    draw_grid(img, BIRD, 3, 6, 1)
    return img


def save_icons():
    base = icon_grid()
    sizes = {'mdpi': 2, 'hdpi': 3, 'xhdpi': 4, 'xxhdpi': 6, 'xxxhdpi': 8}
    for dens, s in sizes.items():
        d = os.path.join(RES, f'mipmap-{dens}')
        os.makedirs(d, exist_ok=True)
        big = base.resize((24 * s, 24 * s), Image.NEAREST)
        # rounded square for the legacy icon
        mask = Image.new('L', big.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big.size[0] - 1, big.size[1] - 1], radius=4 * s, fill=255)
        sq = Image.new('RGBA', big.size, (0, 0, 0, 0))
        sq.paste(big, (0, 0), mask)
        sq.save(os.path.join(d, 'ic_launcher.png'))
        rmask = Image.new('L', big.size, 0)
        ImageDraw.Draw(rmask).ellipse([0, 0, big.size[0] - 1, big.size[1] - 1], fill=255)
        rd = Image.new('RGBA', big.size, (0, 0, 0, 0))
        rd.paste(big, (0, 0), rmask)
        rd.save(os.path.join(d, 'ic_launcher_round.png'))
    # Adaptive icon: 108dp canvas, 36x36 pixel grid with the art in the central safe zone.
    fg = Image.new('RGBA', (36, 36), (0, 0, 0, 0))
    fg.paste(base, (6, 6))
    nodpi = os.path.join(RES, 'drawable-nodpi')
    os.makedirs(nodpi, exist_ok=True)
    fg.resize((432, 432), Image.NEAREST).save(os.path.join(nodpi, 'ic_launcher_foreground.png'))
    any26 = os.path.join(RES, 'mipmap-anydpi-v26')
    os.makedirs(any26, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/splash_bg" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(any26, name), 'w') as f:
            f.write(xml)
    bird = Image.new('RGBA', (17, 12), (0, 0, 0, 0))
    draw_grid(bird, BIRD, 0, 0, 1)
    pad = Image.new('RGBA', (24, 24), (0, 0, 0, 0))
    pad.paste(bird, (3, 6))
    pad.resize((288, 288), Image.NEAREST).save(os.path.join(nodpi, 'splash_bird.png'))


# ---------------------------------------------------------------- audio
SR = 22050


def note_freq(n):
    """MIDI note number to Hz."""
    return 440.0 * 2 ** ((n - 69) / 12)


def square(phase, duty=0.5):
    return 1.0 if (phase % 1.0) < duty else -1.0


def triangle(phase):
    p = phase % 1.0
    return 4 * p - 1 if p < 0.5 else 3 - 4 * p


def env(t, dur, a=0.005, r=0.05):
    if t < a:
        return t / a
    if t > dur - r:
        return max(0.0, (dur - t) / r)
    return 1.0


def write_wav(name, samples, gain=0.6):
    path = os.path.join(AUDIO, name + '.wav')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    peak = max(1e-6, max(abs(s) for s in samples))
    k = gain / peak
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s * k)) * 32767)) for s in samples))


def sweep(f0, f1, dur, wave_fn=square, duty=0.5, curve=1.0):
    out, ph = [], 0.0
    n = int(SR * dur)
    for i in range(n):
        t = i / SR
        f = f0 + (f1 - f0) * (t / dur) ** curve
        ph += f / SR
        v = wave_fn(ph, duty) if wave_fn is square else wave_fn(ph)
        out.append(v * env(t, dur, 0.002, min(0.04, dur / 3)))
    return out


def seq(notes, step, wave_fn=square, duty=0.5, gap=0.15):
    out = []
    for n in notes:
        if n is None:
            out += [0.0] * int(SR * step)
            continue
        f = note_freq(n)
        ph = 0.0
        L = int(SR * step)
        for i in range(L):
            t = i / SR
            ph += f / SR
            v = wave_fn(ph, duty) if wave_fn is square else wave_fn(ph)
            out.append(v * env(t, step, 0.002, step * gap))
    return out


def noise(dur, decay=20.0, lowpass=0.3):
    rnd = random.Random(7)
    out, prev = [], 0.0
    for i in range(int(SR * dur)):
        t = i / SR
        prev += lowpass * (rnd.uniform(-1, 1) - prev)
        out.append(prev * math.exp(-decay * t))
    return out


def mix(*tracks):
    n = max(len(t) for t in tracks)
    return [sum(t[i] if i < len(t) else 0.0 for t in tracks) for i in range(n)]


def save_sfx():
    write_wav('sfx_flap', mix(sweep(380, 760, 0.07, square, 0.25), [x * 0.5 for x in noise(0.06, 50, 0.5)]), 0.45)
    write_wav('sfx_score', seq([88, 95], 0.06, square, 0.5), 0.4)
    write_wav('sfx_coin', seq([93, 100], 0.05, square, 0.25), 0.35)
    write_wav('sfx_hit', mix(noise(0.22, 14, 0.25), sweep(220, 70, 0.18, square, 0.5)), 0.7)
    write_wav('sfx_die', sweep(700, 120, 0.45, square, 0.5, 0.7), 0.45)
    write_wav('sfx_capsule', seq([76, 79, 83, 88], 0.055, square, 0.25), 0.4)
    write_wav('sfx_gem', seq([96, 100, 103], 0.045, triangle), 0.5)
    write_wav('sfx_rare', mix(seq([72, 76, 79, 84, 88, 91, 96, None], 0.07, square, 0.25),
                              seq([60, None, 64, None, 67, None, 72, None], 0.07, triangle)), 0.5)
    write_wav('sfx_click', sweep(1400, 900, 0.025, square, 0.5), 0.3)
    write_wav('sfx_win', mix(seq([72, 76, 79, 84, None, 79, 84, 88, 88, 88], 0.09, square, 0.5),
                             seq([48, 48, 55, 55, 52, 52, 55, 60, 60, 60], 0.09, triangle)), 0.5)
    write_wav('sfx_buy', seq([84, 88, 91], 0.05, square, 0.5), 0.35)


def song(bpm, bars, chords, melody, lead_duty, name, swing_hat=True):
    """Simple 4-part chiptune: square lead, square arpeggio, triangle bass, noise hats."""
    step = 60.0 / bpm / 4  # sixteenth notes
    total = bars * 16
    lead, arp, bass, hats = [], [], [], []
    for s in range(total):
        bar = s // 16
        chord = chords[bar % len(chords)]
        # lead: melody uses one entry per eighth note
        m = melody[(s // 2) % len(melody)] if s % 2 == 0 else 'hold'
        if m != 'hold':
            lead.append(m)
        # arpeggio every sixteenth
        arp.append(chord[s % 3] + 12)
        # bass on quarter notes
        bass.append(chord[0] - 12 if s % 4 == 0 else ('hold' if s % 4 != 3 else None))
        hats.append(s % 2 == 0)
    out_len = int(SR * step * total)
    buf = [0.0] * out_len
    # lead (eighth notes)
    pos = 0
    for n in lead:
        L = int(SR * step * 2)
        if n is not None:
            f = note_freq(n)
            ph = 0.0
            for i in range(L):
                if pos + i >= out_len:
                    break
                t = i / SR
                ph += f / SR
                vib = 1 + 0.004 * math.sin(2 * math.pi * 5.5 * t)
                buf[pos + i] += 0.30 * square(ph * vib, lead_duty) * env(t, step * 2, 0.004, step * 0.6)
        pos += L
    pos = 0
    for n in arp:
        L = int(SR * step)
        f = note_freq(n)
        ph = 0.0
        for i in range(L):
            if pos + i >= out_len:
                break
            t = i / SR
            ph += f / SR
            buf[pos + i] += 0.08 * square(ph, 0.125) * env(t, step, 0.002, step * 0.5)
        pos += L
    pos, cur, ph = 0, None, 0.0
    for b in bass:
        L = int(SR * step)
        if b != 'hold':
            cur = b
        for i in range(L):
            if pos + i >= out_len:
                break
            if cur is not None:
                ph += note_freq(cur) / SR
                buf[pos + i] += 0.32 * triangle(ph)
        pos += L
    rnd = random.Random(3)
    pos = 0
    for h in hats:
        L = int(SR * step)
        if h:
            prev = 0.0
            for i in range(min(L, int(SR * 0.03))):
                prev += 0.9 * (rnd.uniform(-1, 1) - prev)
                if pos + i < out_len:
                    buf[pos + i] += 0.05 * prev * math.exp(-120 * i / SR)
        pos += L
    write_wav(name, buf, 0.55)


def save_music():
    C, Am, F, G, Em, Dm = [60, 64, 67], [57, 60, 64], [53, 57, 60], [55, 59, 62], [52, 55, 59], [50, 53, 57]
    menu_mel = [72, None, 76, 79, 81, None, 79, 76, 77, None, 76, 74, 72, None, None, None,
                69, None, 72, 76, 74, None, 72, 69, 67, None, 69, 71, 72, None, None, None]
    song(100, 8, [C, Am, F, G], menu_mel, 0.5, 'music_menu')
    game_mel = [76, 76, None, 79, 81, 79, 76, 74, 72, None, 74, 76, 74, 72, 69, None,
                72, 72, None, 76, 77, 76, 74, 72, 71, None, 72, 74, 76, None, 79, None]
    song(140, 16, [C, G, Am, Em, F, C, Dm, G], game_mel, 0.25, 'music_game')


def save_ios_assets():
    """iOS: one 1024x1024 opaque app icon (iOS applies the rounded mask) and launch screen assets."""
    import json
    def write_json(path, data):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            json.dump(data, f, indent=2)
    write_json(os.path.join(XCASSETS, 'Contents.json'), {'info': {'author': 'xcode', 'version': 1}})
    icon_dir = os.path.join(XCASSETS, 'AppIcon.appiconset')
    big = icon_grid().resize((24 * 42, 24 * 42), Image.NEAREST)
    canvas = Image.new('RGB', (1024, 1024), SKY_TOP)
    canvas.paste(big, (8, 8), big)
    os.makedirs(icon_dir, exist_ok=True)
    canvas.save(os.path.join(icon_dir, 'icon-1024.png'))
    write_json(os.path.join(icon_dir, 'Contents.json'), {
        'images': [{'filename': 'icon-1024.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'}],
        'info': {'author': 'xcode', 'version': 1}})
    bird_dir = os.path.join(XCASSETS, 'LaunchBird.imageset')
    bird = Image.new('RGBA', (17, 12), (0, 0, 0, 0))
    draw_grid(bird, BIRD, 0, 0, 1)
    os.makedirs(bird_dir, exist_ok=True)
    for scale in (1, 2, 3):
        bird.resize((17 * 6 * scale, 12 * 6 * scale), Image.NEAREST).save(os.path.join(bird_dir, f'launch_bird@{scale}x.png'))
    write_json(os.path.join(bird_dir, 'Contents.json'), {
        'images': [{'filename': f'launch_bird@{s}x.png', 'idiom': 'universal', 'scale': f'{s}x'} for s in (1, 2, 3)],
        'info': {'author': 'xcode', 'version': 1}})
    r, g, b = SKY_TOP
    write_json(os.path.join(XCASSETS, 'LaunchBackground.colorset', 'Contents.json'), {
        'colors': [{'color': {'color-space': 'srgb', 'components': {'red': f'{r / 255:.3f}', 'green': f'{g / 255:.3f}', 'blue': f'{b / 255:.3f}', 'alpha': '1.000'}}, 'idiom': 'universal'}],
        'info': {'author': 'xcode', 'version': 1}})


if __name__ == '__main__':
    save_icons()
    save_ios_assets()
    save_sfx()
    save_music()
    print('assets written to', os.path.normpath(RES))
