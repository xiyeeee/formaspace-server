package com.xiye.formaspace.testsupport;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

public final class MediaFixtures {
    private MediaFixtures() {}
    public static byte[] png() throws IOException {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", output);
        image.flush();
        return output.toByteArray();
    }

    public static byte[] glb() {
        // A self-contained single triangle: fixture only, never installed as an application demo asset.
        String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"buffers":[{"byteLength":36}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]}]}
                """;
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        int aligned = (raw.length + 3) / 4 * 4;
        var glb = ByteBuffer.allocate(12 + 8 + aligned + 8 + 36).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity()).putInt(aligned).putInt(0x4E4F534A).put(raw);
        while (glb.position() < 20 + aligned) { glb.put((byte) ' '); }
        glb.putInt(36).putInt(0x004E4942);
        for (float coordinate : new float[] {0,0,0, 1,0,0, 0,1,0}) { glb.putFloat(coordinate); }
        return glb.array();
    }
}
