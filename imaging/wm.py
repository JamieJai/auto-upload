"""반복 격자 워터마크(같은 도장이 같은 자리에 찍힌 사진들) 추정·제거.

추정 (같은 크기·같은 워터마크 사진 여러 장):
  1) 그래디언트 중앙값 → 포아송 적분으로 워터마크 모양(shape) 추정
     (Dekel et al., "On the Effectiveness of Visible Watermarks", CVPR 2017 의 단순화)
  2) 모양에서 반복 도장(tile)과 격자 위치를 찾는다
  3) 도장 좌표계에서 모든 복사본 x 모든 사진으로 픽셀마다 I = g*J + b 를 최소제곱 추정
     (J 는 각 사진에서 워터마크 자리를 주변으로 메운 값. 255 로 잘린 표본은 뺀다)
제거: J = (I - b) / g. 정보가 잘린 픽셀과 글자 테두리만 주변으로 메우되, 밝은 배경에서는 메우지 않는다.
"""
import numpy as np
import cv2
from scipy.fft import dstn, idstn


def _poisson(gx, gy):
    gx = gx / 8.0
    gy = gy / 8.0
    div = np.zeros_like(gx)
    div[:, 1:] += gx[:, 1:] - gx[:, :-1]
    div[1:, :] += gy[1:, :] - gy[:-1, :]
    f = dstn(div, type=1)
    yy, xx = np.meshgrid(np.arange(1, div.shape[0] + 1), np.arange(1, div.shape[1] + 1), indexing="ij")
    denom = (2 * np.cos(np.pi * xx / (div.shape[1] + 1)) - 2) + (2 * np.cos(np.pi * yy / (div.shape[0] + 1)) - 2)
    return idstn(f / denom, type=1)


def estimate_shape(imgs):
    gxs, gys = [], []
    for im in imgs:
        g = cv2.cvtColor(im.astype(np.uint8), cv2.COLOR_BGR2GRAY).astype(np.float32)
        gxs.append(cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3))
        gys.append(cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3))
    mgx = np.median(np.stack(gxs), axis=0)
    mgy = np.median(np.stack(gys), axis=0)
    mag = np.hypot(mgx, mgy)
    edge = (mag > np.percentile(mag, 97)).astype(np.uint8)
    region = cv2.dilate(edge, np.ones((15, 15), np.uint8))
    wm = _poisson(mgx * region, mgy * region)
    wm -= np.median(wm[region == 0])
    wm = np.clip(wm, 0, None) * region
    if wm.max() <= 0:
        raise ValueError("워터마크를 찾지 못했습니다 (사진들에 같은 자리 워터마크가 있어야 합니다)")
    return (wm / wm.max()).astype(np.float32)


def find_lattice(shape):
    H, W = shape.shape
    blob = cv2.dilate((shape > 0.2).astype(np.uint8), np.ones((41, 41), np.uint8))
    n, _, stats, _ = cv2.connectedComponentsWithStats(blob)
    boxes = [stats[i] for i in range(1, n) if stats[i][4] > 2000]
    full = [b for b in boxes if b[0] > 0 and b[1] > 0 and b[0] + b[2] < W and b[1] + b[3] < H]
    if not full:
        raise ValueError("사진 안에 온전한 워터마크 도장이 없습니다")
    ref = max(full, key=lambda b: b[4])
    pad = 30
    tx, ty, tw, th = ref[0] - pad, ref[1] - pad, ref[2] + 2 * pad, ref[3] + 2 * pad
    tx, ty = max(0, tx), max(0, ty)
    tw, th = min(tw, W - tx), min(th, H - ty)
    tile = shape[ty:ty + th, tx:tx + tw]
    P = np.pad(shape, ((th, th), (tw, tw)))
    res = cv2.matchTemplate(P.astype(np.float32), tile.astype(np.float32), cv2.TM_CCORR)
    res /= res.max()
    peaks = []
    r = res.copy()
    while True:
        _, mx, _, (x, y) = cv2.minMaxLoc(r)
        if mx < 0.25 or len(peaks) > 60:
            break
        peaks.append((x - tw, y - th, mx))
        r[max(0, y - th // 2):y + th // 2, max(0, x - tw // 2):x + tw // 2] = 0
    peaks.sort(key=lambda p: -p[2])
    x0, y0, _ = peaks[0]
    others = [(x - x0, y - y0) for x, y, _ in peaks[1:]]
    horiz = [o for o in others if abs(o[0]) > abs(o[1])]
    vert = [o for o in others if abs(o[1]) > abs(o[0])]
    if not horiz or not vert:
        # 반복이 아닌 워터마크: 찾은 위치만 쓴다
        return tile, [(int(x), int(y)) for x, y, _ in peaks]
    v1 = min(horiz, key=lambda o: abs(o[0]))
    v2 = min(vert, key=lambda o: abs(o[1]))
    locs = []
    for i in range(-6, 7):
        for j in range(-6, 7):
            x = x0 + i * v1[0] + j * v2[0]
            y = y0 + i * v1[1] + j * v2[1]
            if x + tw <= 0 or y + th <= 0 or x >= W or y >= H:
                continue
            px, py = x + tw, y + th
            ys0, xs0 = max(0, py - 6), max(0, px - 6)
            win = res[ys0:py + 7, xs0:px + 7]
            if win.size:
                dy, dx = np.unravel_index(np.argmax(win), win.shape)
                x, y = xs0 + dx - tw, ys0 + dy - th
            locs.append((int(x), int(y)))
    return tile, locs


def estimate(paths):
    imgs = [cv2.imread(p, cv2.IMREAD_COLOR) for p in paths]
    imgs = [im for im in imgs if im is not None]
    if len(imgs) < 6:
        raise ValueError("같은 크기 사진이 6장 이상 필요합니다 (현재 %d장)" % len(imgs))
    H, W = imgs[0].shape[:2]
    imgs = [im for im in imgs if im.shape[:2] == (H, W)][:30]
    shape = estimate_shape([im.astype(np.float32) for im in imgs])
    tile, locs = find_lattice(shape)
    th, tw = tile.shape
    mask_full = np.zeros((H, W), np.uint8)
    for x, y in locs:
        ys, xs, ye, xe = max(0, y), max(0, x), min(H, y + th), min(W, x + tw)
        if ye > ys and xe > xs:
            mask_full[ys:ye, xs:xe] |= (tile[ys - y:ye - y, xs - x:xe - x] > 0.04).astype(np.uint8)
    mask_full = cv2.dilate(mask_full, np.ones((5, 5), np.uint8))
    acc = {k: np.zeros((th, tw), np.float64) for k in ["n", "x", "y", "xx", "xy"]}
    for im in imgs:
        jh = cv2.inpaint(im, mask_full, 7, cv2.INPAINT_TELEA).astype(np.float64)
        imf = im.astype(np.float64)
        for x, y in locs:
            ys, xs, ye, xe = max(0, y), max(0, x), min(H, y + th), min(W, x + tw)
            if ye <= ys or xe <= xs:
                continue
            tsl = (slice(ys - y, ye - y), slice(xs - x, xe - x))
            for c in range(3):
                X, Y = jh[ys:ye, xs:xe, c], imf[ys:ye, xs:xe, c]
                ok = Y < 248
                acc["n"][tsl] += ok
                acc["x"][tsl] += X * ok
                acc["y"][tsl] += Y * ok
                acc["xx"][tsl] += X * X * ok
                acc["xy"][tsl] += X * Y * ok
    den = acc["n"] * acc["xx"] - acc["x"] ** 2
    g = np.where(den > 1e-3, (acc["n"] * acc["xy"] - acc["x"] * acc["y"]) / np.maximum(den, 1e-3), 1.0)
    b = (acc["y"] - g * acc["x"]) / np.maximum(acc["n"], 1)
    tmask = cv2.dilate((tile > 0.04).astype(np.uint8), np.ones((5, 5), np.uint8))
    g = np.where(tmask > 0, np.clip(g, 0.3, 1.05), 1.0).astype(np.float32)
    b = np.where(tmask > 0, np.clip(b, -120, 220), 0.0).astype(np.float32)
    return {"g": g, "b": b, "tile": tile, "locs": np.array(locs), "size": np.array([W, H]), "samples": len(imgs)}


def _maps(t, H, W):
    gT, bT, tile = t["g"], t["b"], t["tile"]
    th, tw = gT.shape
    g = np.ones((H, W), np.float32)
    b = np.zeros((H, W), np.float32)
    s = np.zeros((H, W), np.float32)
    for x, y in t["locs"]:
        ys, xs, ye, xe = max(0, y), max(0, x), min(H, y + th), min(W, x + tw)
        if ye <= ys or xe <= xs:
            continue
        sl = (slice(ys - y, ye - y), slice(xs - x, xe - x))
        g[ys:ye, xs:xe] = np.minimum(g[ys:ye, xs:xe], gT[sl])
        b[ys:ye, xs:xe] = np.maximum(b[ys:ye, xs:xe], bT[sl])
        s[ys:ye, xs:xe] = np.maximum(s[ys:ye, xs:xe], tile[sl])
    return g, b, s


def remove(t, im):
    H, W = im.shape[:2]
    if (W, H) != tuple(int(v) for v in t["size"]):
        raise ValueError("템플릿 크기(%dx%d)와 사진 크기(%dx%d)가 다릅니다" % (t["size"][0], t["size"][1], W, H))
    g, b, s = _maps(t, H, W)
    imf = im.astype(np.float32)
    raw = (imf - b[..., None]) / g[..., None]
    lum = cv2.blur(cv2.cvtColor(np.clip(raw, 0, 255).astype(np.uint8), cv2.COLOR_BGR2GRAY), (15, 15))
    inwm = s > 0.04
    bad = inwm & ((imf.max(axis=2) >= 248) | (raw.max(axis=2) > 262) | (raw.min(axis=2) < -8)) & (lum < 225)
    band = cv2.morphologyEx((s > 0.25).astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
    band = (band > 0) & (lum < 225)
    hole = cv2.dilate((band | bad).astype(np.uint8), np.ones((2, 2), np.uint8))
    out = np.clip(raw, 0, 255).astype(np.uint8)
    return cv2.inpaint(out, hole, 3, cv2.INPAINT_TELEA)
