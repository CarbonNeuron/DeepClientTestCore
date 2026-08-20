package dev.deepclient;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepClientHttpServerTest {
    @Test
    void convertsAlphaPngFramebufferToJpeg() throws Exception {
        BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(20, 40, 60, 100).getRGB());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", png));

        byte[] jpeg = DeepClientHttpServer.toJpeg(png.toByteArray());

        assertTrue(jpeg.length > 2);
        assertEquals(0xff, jpeg[0] & 0xff);
        assertEquals(0xd8, jpeg[1] & 0xff);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(jpeg)));
    }
}
