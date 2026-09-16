"""Bundle public-domain roads/rivers/urban areas; no phone-side map downloads.

Pinned upstream source and SHA-256s are recorded alongside the generated asset.
Natural Earth is a regional reference map, not street navigation data.
"""
import gzip
import hashlib
import json
import math
import struct
import array
import sys
from pathlib import Path
from urllib.request import urlopen
from generate_basemap import process_lines, process_polygons

ROOT = Path(__file__).resolve().parents[1]
REVISION = "ca96624a56bd078437bca8184e78163e5039ad19"
BASE = f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{REVISION}/geojson/"

def write_map(out, data):
    def integer(n): out.write(struct.pack(">i", n))
    def string(s):
        b=(s or "").encode("utf-8");integer(len(b));out.write(b)
    def points(coords):
        values=array.array("f")
        previous=None
        for longitude,latitude in coords:
            if previous is not None:
                longitude=previous+(longitude-previous+180)%360-180
            previous=longitude
            lat=math.radians(max(-85.05112878,min(85.05112878,latitude)))
            values.extend(((longitude+180)/360,(1-math.asinh(math.tan(lat))/math.pi)/2))
        bounds=(min(values[::2]),max(values[::2]),min(values[1::2]),max(values[1::2]))
        out.write(struct.pack(">4f",*bounds));integer(len(values))
        if sys.byteorder=="little":values.byteswap()
        out.write(values.tobytes())
    integer(len(data["polygons"]))
    for p in data["polygons"]:
        string(p["name"]);out.write(bytes([p["type"]=="lake"]))
        integer(len(p["rings"]))
        for ring in p["rings"]:points(ring)
    integer(len(data["boundaries"]))
    for b in data["boundaries"]:
        string(b["name"]);string(b["boundaryType"]);points(b["points"])
    integer(len(data["places"]))
    for p in data["places"]:
        string(p["name"])
        lat=math.radians(max(-85.05112878,min(85.05112878,p["lat"])))
        out.write(struct.pack(">2fi",(p["lng"]+180)/360,
            (1-math.asinh(math.tan(lat))/math.pi)/2,p.get("scalerank",5)))

def main():
    result = {"polygons": [], "boundaries": [], "places": []}
    sources = []
    for name, kind in [("ne_10m_roads", "ROAD"),
                       ("ne_10m_rivers_lake_centerlines", "RIVER"),
                       ("ne_10m_urban_areas", "urban")]:
        url = BASE + name + ".geojson"
        with urlopen(url, timeout=120) as response:
            raw = response.read()
        data = json.loads(raw)
        sources.append({"url": url, "sha256": hashlib.sha256(raw).hexdigest()})
        if kind == "urban":
            result["polygons"].extend(process_polygons(data, .001, 4, kind))
        else:
            result["boundaries"].extend(process_lines(data, .0005, 4, kind))
        print(name, len(data["features"]))
    import io
    output=io.BytesIO()
    output.write(b"MAP3\x00\x00\x00\x01")
    write_map(output,json.loads((ROOT/"app/src/main/assets/basemap_regional.json").read_text(encoding="utf-8")))
    write_map(output,result)
    target = ROOT / "app/src/main/assets/basemap_3d.bin.gz"
    target.write_bytes(gzip.compress(output.getvalue(), compresslevel=9, mtime=0))
    (ROOT / "docs/verification-3d/cartography-sources.json").write_text(
        json.dumps({"license": "Natural Earth public domain", "sources": sources,
                    "regionalAssetSha256": hashlib.sha256((ROOT/"app/src/main/assets/basemap_regional.json").read_bytes()).hexdigest(),
                    "assetSha256": hashlib.sha256(target.read_bytes()).hexdigest()}, indent=2) + "\n")
    print("Bundled bytes:", target.stat().st_size)

if __name__ == "__main__":
    main()
