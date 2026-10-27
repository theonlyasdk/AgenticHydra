"""Check WCAG AA normal-text token pairs used by both Android themes."""
def luminance(color):
    rgb = [int(color[i:i+2], 16) / 255 for i in (0, 2, 4)]
    linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in rgb]
    return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))

pairs = {
    'Light body': ('182630', 'F3F7FA'),
    'Light secondary': ('536570', 'F3F7FA'),
    'Light secondary/composer': ('536570', 'FFFFFF'),
    'Light primary': ('006B75', 'F3F7FA'),
    'Light button': ('FFFFFF', '006B75'),
    'Light tonal': ('174B52', 'D5EEF0'),
    'Light error': ('752020', 'FBE5E3'),
    'Dark body': ('DFEAF0', '10191F'),
    'Dark secondary': ('ABBDC8', '10191F'),
    'Dark secondary/composer': ('ABBDC8', '19262E'),
    'Dark primary': ('80D5DE', '10191F'),
    'Dark button': ('00363C', '80D5DE'),
    'Dark tonal': ('BDECF0', '173A41'),
    'Dark error': ('FFDAD6', '512727'),
}
for name, (fg, bg) in pairs.items():
    a, b = sorted((luminance(fg), luminance(bg)))
    ratio = (b + .05) / (a + .05)
    assert ratio >= 4.5, f'{name} fails AA: {ratio:.2f}'
    print(f'{name}: {ratio:.2f}:1 — PASS')
