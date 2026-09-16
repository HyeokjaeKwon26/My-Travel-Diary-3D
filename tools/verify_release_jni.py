"""Check native zstd fields and ESRI resource loading in the packaged release DEX.
R8 mapping omits identity fields; inspecting the APK checks the real JNI contract.
"""
from pathlib import Path
import struct
import sys
import zipfile


def fields_for(data, descriptor):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    def leb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7
            assert shift <= 28, "Invalid DEX integer"
    strings = []
    for i in range(u32(0x38)):
        _, start = leb(u32(u32(0x3C) + i * 4))
        strings.append(data[start:data.index(b"\0", start)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(0x44) + i * 4)] for i in range(u32(0x40))]
    for i in range(u32(0x60)):
        record = u32(0x64) + i * 32
        if types[u32(record)] != descriptor:
            continue
        offset = u32(record + 24)
        sizes = []
        for _ in range(4):
            size, offset = leb(offset)
            sizes.append(size)
        fields = {}
        for size in sizes[:2]:
            index = 0
            for _ in range(size):
                delta, offset = leb(offset)
                _, offset = leb(offset)
                index += delta
                _, type_index, name_index = struct.unpack_from("<HHI", data, u32(0x54) + index * 8)
                fields[strings[name_index]] = types[type_index]
        return fields
    return None


if __name__ == "__main__":
    apks = [Path(sys.argv[1])] if len(sys.argv) > 1 else list(Path("app/build/outputs/apk/release").glob("*.apk"))
    assert len(apks) == 1, "Specify exactly one release APK"
    expected = "Lcom/github/luben/zstd/ZstdInputStreamNoFinalizer;"
    fields = wkid = None
    with zipfile.ZipFile(apks[0]) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                data = apk.read(name)
                if fields is None:
                    fields = fields_for(data, expected)
                if wkid is None:
                    wkid = fields_for(data, "Lcom/esri/core/geometry/Wkid;")
        assert fields is not None, "Native stream class was removed or renamed"
        for name in ("srcPos", "dstPos"):
            assert fields.get(name) == "J", f"Native long field missing or renamed: {name}"
        assert wkid is not None, "ESRI Wkid package moved away from its relative resources"
        assert "com/esri/core/geometry/gcs_tolerances.txt" in apk.namelist(), "ESRI coordinate-system data missing"
        with apk.open("assets/basemap_3d.bin") as basemap:
            assert basemap.read(8) == b"MAP3\x00\x00\x00\x01", "Bundled 3D cartography missing or invalid"
    print("Release contracts passed: native fields, ESRI resources, bundled 3D cartography")
