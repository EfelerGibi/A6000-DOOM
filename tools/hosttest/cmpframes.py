"""Compare two hosttest 'frames' outputs by gametic.

Each record is a little-endian int32 gametic followed by a 640x400 RGB565
frame. For every gametic present in both files, the last frame drawn at that
gametic must be byte-identical.
"""
import struct
import sys

FS = 640 * 400 * 2


def load(path):
    data = open(path, "rb").read()
    frames = {}
    for off in range(0, len(data) - 4 - FS + 1, 4 + FS):
        gt = struct.unpack_from("<i", data, off)[0]
        frames[gt] = data[off + 4:off + 4 + FS]
    return frames


a, b = load(sys.argv[1]), load(sys.argv[2])
common = sorted(set(a) & set(b))
bad = [gt for gt in common if a[gt] != b[gt]]
print("gametics compared: %d (%d..%d), differing: %d"
      % (len(common), common[0] if common else -1, common[-1] if common else -1, len(bad)))
if bad:
    print("first differing gametics:", bad[:10])
sys.exit(1 if bad or len(common) < 10 else 0)
