"""이미지 처리 서비스 (내부망 전용). 백엔드만 부른다. 경로는 모두 /data 아래.

POST /templates   {"name", "images": [path...]}  → 같은 크기 사진들로 워터마크 템플릿 추정, /data/watermarks/{name}.npz
GET  /templates                                   → 템플릿 목록 (이름, 크기, 표본 수)
POST /remove      {"template", "src", "dst", "retouch"?} → src 의 워터마크를 지워(리터치 포함) dst 에 JPEG 로 저장
"""
import json, os, re, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import cv2

import wm

ROOT = os.environ.get("DATA_ROOT", "/data")
TPL = os.path.join(ROOT, "watermarks")
os.makedirs(TPL, exist_ok=True)
NAME = re.compile(r"^[a-z0-9_-]{2,60}$")
_cache = {}


def safe(path):
    p = os.path.realpath(os.path.join(ROOT, path.lstrip("/")))
    if not p.startswith(os.path.realpath(ROOT) + os.sep):
        raise ValueError("잘못된 경로")
    return p


def load(name):
    if not NAME.match(name):
        raise ValueError("잘못된 템플릿 이름")
    f = os.path.join(TPL, name + ".npz")
    mtime = os.path.getmtime(f)
    if name not in _cache or _cache[name][0] != mtime:
        d = np.load(f)
        _cache[name] = (mtime, {k: d[k] for k in d.files})
    return _cache[name][1]


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"ok": True})
        if self.path == "/templates":
            out = []
            for f in sorted(os.listdir(TPL)):
                if f.endswith(".npz"):
                    t = load(f[:-4])
                    out.append({"name": f[:-4], "width": int(t["size"][0]), "height": int(t["size"][1]),
                                "samples": int(t.get("samples", 0)), "copies": int(len(t["locs"]))})
            return self._send(200, out)
        self._send(404, {"message": "not found"})

    def do_POST(self):
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            if self.path == "/templates":
                name = req["name"]
                if not NAME.match(name):
                    raise ValueError("템플릿 이름은 영문 소문자·숫자·_- 2~60자")
                t0 = time.time()
                t = wm.estimate([safe(p) for p in req["images"]])
                np.savez_compressed(os.path.join(TPL, name + ".npz"), **t)
                prev = (t["tile"] / max(t["tile"].max(), 1e-6) * 255).astype(np.uint8)
                cv2.imwrite(os.path.join(TPL, name + "_preview.png"), prev)
                return self._send(200, {"name": name, "width": int(t["size"][0]), "height": int(t["size"][1]),
                                        "samples": int(t["samples"]), "copies": int(len(t["locs"])),
                                        "seconds": round(time.time() - t0, 1)})
            if self.path == "/remove":
                t = load(req["template"])
                im = cv2.imread(safe(req["src"]), cv2.IMREAD_COLOR)
                if im is None:
                    raise ValueError("사진을 읽을 수 없습니다")
                out = wm.remove(t, im, bool(req.get("retouch", True)))
                dst = safe(req["dst"])
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                tmp = dst + ".tmp.jpg"
                cv2.imwrite(tmp, out, [cv2.IMWRITE_JPEG_QUALITY, 93])
                os.replace(tmp, dst)
                return self._send(200, {"ok": True, "width": int(out.shape[1]), "height": int(out.shape[0])})
            self._send(404, {"message": "not found"})
        except (ValueError, KeyError, FileNotFoundError) as e:
            self._send(400, {"message": str(e)})
        except Exception as e:  # noqa: BLE001
            self._send(500, {"message": f"{type(e).__name__}: {e}"})

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), H).serve_forever()
