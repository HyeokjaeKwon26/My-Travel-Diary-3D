"""Prepare redistributable offline graphics. Network use is developer-side only.

Terrain: Mapzen Terrain Tiles / USGS 3DEP, accessed from the AWS Open Data bucket.
World texture: existing Natural Earth public-domain world polygons.
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
from urllib.request import urlopen
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def make_world():
    world = json.loads((ASSETS / 'basemap_world.json').read_text())
    image = Image.new('RGB', (2048, 1024), '#163a54')
    draw = ImageDraw.Draw(image)
    for poly in world['polygons']:
        for i, ring in enumerate(poly['rings']):
            points = [((lng + 180) / 360 * 2048, (90 - lat) / 180 * 1024) for lng, lat in ring]
            color = '#53786d' if poly['type'] == 'land' and i == 0 else '#163a54'
            draw.polygon(points, fill=color)
    for boundary in world['boundaries']:
        points = [((lng + 180) / 360 * 2048, (90 - lat) / 180 * 1024) for lng, lat in boundary['points']]
        if len(points) >= 2: draw.line(points, fill='#668880', width=1)
    image.save(ASSETS / 'earth_3d.png', optimize=True)


def make_terrain(north, south, west, east, name, output, samples=129, zoom=11):
    tiles = {}
    sources = {}
    def height(lat, lng):
        n = 2 ** zoom
        x = (lng + 180) / 360 * n
        y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
        tx, ty = math.floor(x), math.floor(y)
        key = (tx, ty)
        if key not in tiles:
            url = f'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{zoom}/{tx}/{ty}.png'
            with urlopen(url, timeout=30) as r: raw = r.read()
            tiles[key] = Image.open(io.BytesIO(raw)).convert('RGB')
            sources[url] = hashlib.sha256(raw).hexdigest()
        r, g, b = tiles[key].getpixel((min(255, int((x-tx)*256)), min(255, int((y-ty)*256))))
        return round(r * 256 + g + b / 256 - 32768, 1)
    values = [height(north-(north-south)*r/(samples-1), west+(east-west)*c/(samples-1))
              for r in range(samples) for c in range(samples)]
    pack = dict(version=1, name=name, attribution='Terrain: Mapzen / USGS 3DEP, GMTED2010, SRTM',
                verticalDatum='Source DEM vertical datum; not GPS ellipsoid altitude',
                north=north, south=south, west=west, east=east, rows=samples, columns=samples, heights=values)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(pack, separators=(',', ':')), encoding='utf-8')
    output.with_suffix('.sources.txt').write_text('\n'.join(f'{sha}  {url}' for url,sha in sorted(sources.items())))
    print(f'{output}: {len(values)} heights, {len(tiles)} source tiles, elevation {min(values)}..{max(values)} m')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bounds', nargs=4, type=float, default=[36.23, 35.96, -112.25, -111.75], metavar=('N','S','W','E'))
    parser.add_argument('--name', default='Grand Canyon South Rim')
    parser.add_argument('--output', type=Path, default=ASSETS/'terrain/grand_canyon.terrain.json')
    args = parser.parse_args()
    make_world()
    make_terrain(*args.bounds, args.name, args.output)
