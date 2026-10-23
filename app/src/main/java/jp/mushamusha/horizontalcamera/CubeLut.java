package jp.mushamusha.horizontalcamera;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** .cube 3D LUT parser (LUT_3D_SIZE 2..64) with DOMAIN_MIN/MAX support. */
public final class CubeLut {
    public final int size;
    public final FloatBuffer data;
    public final float[] domainMin;
    public final float[] domainMax;

    private CubeLut(int size, FloatBuffer data, float[] domainMin, float[] domainMax) {
        this.size = size;
        this.data = data;
        this.domainMin = domainMin;
        this.domainMax = domainMax;
    }

    public static CubeLut parse(InputStream input) throws IOException {
        if (input == null) throw new IOException("LUT input is null");
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        int size = -1;
        int expectedRows = -1;
        int rowCount = 0;
        FloatBuffer buffer = null;
        float[] domainMin = new float[]{0f, 0f, 0f};
        float[] domainMax = new float[]{1f, 1f, 1f};
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) continue;
            if (line.startsWith("LUT_1D_SIZE")) throw new IOException("1D LUTは未対応です。3D .cube LUTを選択してください");
            if (line.startsWith("DOMAIN_MIN")) {
                domainMin = parseTriple(line, "DOMAIN_MIN", lineNo);
                continue;
            }
            if (line.startsWith("DOMAIN_MAX")) {
                domainMax = parseTriple(line, "DOMAIN_MAX", lineNo);
                continue;
            }
            if (line.startsWith("LUT_3D_SIZE")) {
                if (buffer != null) throw new IOException("LUT_3D_SIZE is duplicated at line " + lineNo);
                String[] parts = line.split("\\s+");
                if (parts.length < 2) throw new IOException("LUT_3D_SIZE is invalid at line " + lineNo);
                try { size = Integer.parseInt(parts[1]); }
                catch (NumberFormatException e) { throw new IOException("LUT_3D_SIZE is invalid at line " + lineNo, e); }
                if (size < 2 || size > 64) throw new IOException("LUT size must be 2..64");
                expectedRows = size * size * size;
                buffer = ByteBuffer.allocateDirect(expectedRows * 3 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                continue;
            }
            char first = line.charAt(0);
            if (Character.isDigit(first) || first == '-' || first == '+' || first == '.') {
                if (buffer == null) throw new IOException("LUT_3D_SIZE must appear before LUT data (line " + lineNo + ")");
                if (rowCount >= expectedRows) throw new IOException("Too many LUT rows at line " + lineNo);
                String[] parts = line.split("\\s+");
                if (parts.length < 3) throw new IOException("LUT row is invalid at line " + lineNo);
                try {
                    buffer.put(clamp(Float.parseFloat(parts[0])));
                    buffer.put(clamp(Float.parseFloat(parts[1])));
                    buffer.put(clamp(Float.parseFloat(parts[2])));
                } catch (NumberFormatException e) {
                    throw new IOException("LUT row is invalid at line " + lineNo, e);
                }
                rowCount++;
            }
        }
        if (size < 2 || buffer == null) throw new IOException("LUT_3D_SIZE not found");
        for (int i = 0; i < 3; i++) {
            if (!(domainMax[i] > domainMin[i])) throw new IOException("DOMAIN_MIN/MAX is invalid");
        }
        if (rowCount != expectedRows) {
            throw new IOException("LUT data count mismatch: expected " + expectedRows + ", got " + rowCount);
        }
        buffer.flip();
        return new CubeLut(size, buffer, domainMin, domainMax);
    }

    private static float[] parseTriple(String line, String key, int lineNo) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) throw new IOException(key + " is invalid at line " + lineNo);
        try {
            return new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3])};
        } catch (NumberFormatException e) {
            throw new IOException("LUT row is invalid at line " + lineNo, e);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
