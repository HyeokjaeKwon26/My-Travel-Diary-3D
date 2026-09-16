import com.github.luben.zstd.ZstdInputStream;
import net.iakovlev.timeshape.TimeZoneEngine;
import net.iakovlev.timeshape.proto.Geojson;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Run with the pinned TimeShape dependency classpath. Preserves full source geometry. */
class BuildTimezoneRegions {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]); Files.createDirectories(output.getParent());
        int entries=0, polygons=0;
        try (var data=TimeZoneEngine.class.getResourceAsStream("/data.tar.zstd");
             var tar=new TarArchiveInputStream(new BufferedInputStream(new ZstdInputStream(data)));
             var writer=Files.newBufferedWriter(output)) {
            writer.write("# TimeShape 2026b.29: archive entry, IANA zone, polygon bounds (lon/lat)\n");
            for (var entry=tar.getNextTarEntry();entry!=null;entry=tar.getNextTarEntry()) {
                var feature=Geojson.Feature.parseFrom(tar.readNBytes(Math.toIntExact(entry.getSize())));
                String zone=feature.getProperties(0).getValueString();
                var geometry=feature.getGeometry();
                var shapes=geometry.hasPolygon()?List.of(geometry.getPolygon()):geometry.getMultiPolygon().getCoordinatesList();
                for(var polygon:shapes) {
                    double minX=180,minY=90,maxX=-180,maxY=-90;
                    for(var ring:polygon.getCoordinatesList()) for(var point:ring.getCoordinatesList()) {
                        minX=Math.min(minX,point.getLon()); maxX=Math.max(maxX,point.getLon());
                        minY=Math.min(minY,point.getLat()); maxY=Math.max(maxY,point.getLat());
                    }
                    writer.write(String.join("\t",entry.getName(),zone,Double.toString(minX),Double.toString(minY),Double.toString(maxX),Double.toString(maxY))+"\n");
                    polygons++;
                }
                entries++;
            }
        }
        System.out.println(entries+" zones, "+polygons+" polygon bounds: "+Files.size(output)+" bytes");
    }
}
