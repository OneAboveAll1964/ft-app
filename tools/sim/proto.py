import struct


def enc_varint(v):
    v &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tag(field, wire):
    return enc_varint((field << 3) | wire)


def f_varint(field, v):
    return tag(field, 0) + enc_varint(v)


def f_sint32(field, v):
    return f_varint(field, ((v << 1) ^ (v >> 31)) & 0xFFFFFFFF)


def f_bytes(field, b):
    return tag(field, 2) + enc_varint(len(b)) + b


def f_str(field, s):
    return f_bytes(field, s.encode("utf-8"))


def f_bool(field, b):
    return f_varint(field, 1 if b else 0)


def decode(data):
    out = {}
    pos = 0
    n = len(data)

    def varint():
        nonlocal pos
        r = 0
        shift = 0
        while pos < n:
            b = data[pos]
            pos += 1
            r |= (b & 0x7F) << shift
            if not (b & 0x80):
                return r
            shift += 7
        raise ValueError("varint")

    while pos < n:
        t = varint()
        field = t >> 3
        wire = t & 7
        if wire == 0:
            v = varint()
        elif wire == 1:
            v = struct.unpack_from("<Q", data, pos)[0]
            pos += 8
        elif wire == 2:
            l = varint()
            v = bytes(data[pos:pos + l])
            pos += l
        elif wire == 5:
            v = struct.unpack_from("<I", data, pos)[0]
            pos += 4
        else:
            raise ValueError("wire %d" % wire)
        out.setdefault(field, []).append(v)
    return out


def first(d, field, default=None):
    v = d.get(field)
    return v[0] if v else default


def nal_types(annexb):
    types = []
    i = 0
    n = len(annexb)
    while i < n - 3:
        if annexb[i] == 0 and annexb[i + 1] == 0 and (annexb[i + 2] == 1 or (annexb[i + 2] == 0 and i + 3 < n and annexb[i + 3] == 1)):
            j = i + (3 if annexb[i + 2] == 1 else 4)
            if j < n:
                types.append(annexb[j] & 0x1F)
            i = j
        else:
            i += 1
    return types


def split_nals(annexb):
    starts = []
    i = 0
    n = len(annexb)
    while i < n - 3:
        if annexb[i] == 0 and annexb[i + 1] == 0 and (annexb[i + 2] == 1 or (annexb[i + 2] == 0 and i + 3 < n and annexb[i + 3] == 1)):
            starts.append(i)
            i += 3
        else:
            i += 1
    nals = []
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else n
        nals.append(bytes(annexb[s:e]))
    return nals
