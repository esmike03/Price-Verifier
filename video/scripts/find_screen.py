"""Locate the display quad in the device photo and write a blanked-screen copy.

Usage: python find_screen.py <device-original.png> <device.png> <debug.png>
Prints the four screen corners (TL, TR, BR, BL) in image pixels.
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

src, dst, debug = sys.argv[1:4]
img = Image.open(src).convert("RGBA")
a = np.asarray(img).astype(np.float32)
r, g, b, alpha = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
mx = np.maximum(np.maximum(r, g), b)
mn = np.minimum(np.minimum(r, g), b)
sat = (mx - mn) / np.maximum(mx, 1)

# The lit display is the only large saturated region; the bezel is black and
# the housing is grey. Keep the biggest connected blob to drop the green LED.
mask = (sat > 0.45) & (mx > 90) & (alpha > 200)

h, w = mask.shape
labels = np.zeros((h, w), np.int32)
best, best_size, cur = 0, 0, 0
for y0, x0 in zip(*np.nonzero(mask)):
    if labels[y0, x0]:
        continue
    cur += 1
    stack = [(y0, x0)]
    labels[y0, x0] = cur
    size = 0
    while stack:
        y, x = stack.pop()
        size += 1
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not labels[ny, nx]:
                labels[ny, nx] = cur
                stack.append((ny, nx))
    if size > best_size:
        best, best_size = cur, size

screen = labels == best
ys, xs = np.nonzero(screen)
print("screen pixels:", best_size, "bbox:", xs.min(), ys.min(), xs.max(), ys.max())


def fit_edge(points):
    """Least-squares line through boundary points, as (point, direction)."""
    pts = np.asarray(points, np.float64)
    c = pts.mean(axis=0)
    _, _, vt = np.linalg.svd(pts - c)
    return c, vt[0]


def intersect(l1, l2):
    (p, d), (q, e) = l1, l2
    t = np.linalg.solve(np.array([d, -e]).T, q - p)
    return p + t[0] * d


# Sample each edge away from the corners so rounded corners don't bias the fit.
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
cols = range(int(x0 + 0.2 * (x1 - x0)), int(x0 + 0.8 * (x1 - x0)), 3)
rows = range(int(y0 + 0.35 * (y1 - y0)), int(y0 + 0.65 * (y1 - y0)), 3)
top = [(x, np.nonzero(screen[:, x])[0].min()) for x in cols]
bottom = [(x, np.nonzero(screen[:, x])[0].max()) for x in cols]
left = [(np.nonzero(screen[y, :])[0].min(), y) for y in rows]
right = [(np.nonzero(screen[y, :])[0].max(), y) for y in rows]

lt, lb, ll, lr = fit_edge(top), fit_edge(bottom), fit_edge(left), fit_edge(right)
tl, tr, br, bl = intersect(lt, ll), intersect(lt, lr), intersect(lb, lr), intersect(lb, ll)
corners = [tl, tr, br, bl]
print("corners TL TR BR BL:", [[round(float(v), 2) for v in c] for c in corners])

# Blank the original display (slightly grown) so nothing shows around the overlay.
centre = np.mean(corners, axis=0)
grown = [tuple(c + (c - centre) / np.linalg.norm(c - centre) * 2.5) for c in corners]
out = img.copy()
ImageDraw.Draw(out).polygon(grown, fill=(8, 9, 12, 255))
out.save(dst)

dbg = img.copy()
d = ImageDraw.Draw(dbg)
d.line([tuple(c) for c in corners] + [tuple(corners[0])], fill=(255, 0, 0, 255), width=1)
for c in corners:
    d.ellipse([c[0] - 3, c[1] - 3, c[0] + 3, c[1] + 3], outline=(255, 255, 0, 255))
dbg.resize((1400, 1400), Image.LANCZOS).save(debug)
