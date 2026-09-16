# Offline terrain packs

The Android app does not request INTERNET permission. Terrain is loaded from bundled
or user-imported files; location history is never sent to an elevation service.

The bundled **Grand Canyon South Rim** grid uses real elevation samples from
[Mapzen Terrain Tiles on AWS](https://registry.opendata.aws/terrain-tiles/),
accessed 2026-09-15. US terrain data: Mapzen / USGS 3DEP (formerly NED), GMTED2010
and SRTM, courtesy of the U.S. Geological Survey. The sample route is an
**illustrative route**, not a recorded journey or a verified road alignment.

Attribution/source terms:
https://github.com/tilezen/joerd/blob/master/docs/attribution.md

World artwork is rendered from the existing Natural Earth public-domain polygons.

## Import

Open a trip → **3D • Terrain** → **Import terrain pack**. Pick a `.terrain.json`
file. The bundled region and up to three imported regional grids are held locally.
Imported packs can be removed from the same dialog. Original trips are not removed.

Format version 1 is a WGS84 latitude/longitude grid with north-to-south rows and
west-to-east columns. Required fields:

```json
{
  "version": 1,
  "name": "Example region",
  "attribution": "Data provider and required attribution",
  "verticalDatum": "Named source vertical datum",
  "north": 36.2, "south": 36.0,
  "west": -112.2, "east": -112.0,
  "rows": 2, "columns": 2,
  "heights": [1800.0, 1850.0, 1790.0, null]
}
```

Heights are meters; `null` means missing data. A cell with a missing corner is not
interpolated. Bounds cannot cross the date line; use separate regional packs.
Limits: 4 MB per file, at most 257×257 samples, at most 10° per side. These keep
untrusted input and GPU geometry bounded. A SHA-256-derived filename identifies
the validated canonical contents; it does not authenticate the source provider.

## Build another region on a computer

Install Python and Pillow, then run:

```sh
python tools/build_3d_assets.py --bounds NORTH SOUTH WEST EAST --name "Region" --output region.terrain.json
```

This **developer-side command uses the internet** to download public elevation
tiles. The app itself remains offline. Review the source-specific attribution
before redistributing a region outside the United States. The builder records
source tile URLs and SHA-256 hashes next to the output.

## Accuracy limits

Terrain surface height is not measured vehicle altitude. Rendering uses DEM height
where available so that the path and terrain share the same datum. GPS and DEM
vertical datums are not combined. Isolated recorded altitude impulses are rejected
by the shared route preparation, but road matching, bridge decks, tunnel paths,
and reliable correction of GPS errors along a cliff remain future work.

Outside available grids, a globe is shown without detailed relief. Flight height
without recorded altitude is a visual arc only and is not reported as a measurement.
