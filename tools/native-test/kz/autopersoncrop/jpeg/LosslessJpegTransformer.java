package kz.autopersoncrop.jpeg;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/** Host harness binding the actual Android JNI crop symbol, with synthetic JPEG pixels. */
public final class LosslessJpegTransformer {
    public static final class NativeBridge {
        public static native byte[] losslessCrop(byte[] jpeg, int x, int y, int width, int height);
    }

    public static void main(String[] args) throws Exception {
        System.load(Paths.get(args[0]).toAbsolutePath().toString());
        BufferedImage source = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(3816);
        for (int y = 0; y < source.getHeight(); y++)
            for (int x = 0; x < source.getWidth(); x++)
                source.setRGB(x, y, random.nextInt(0x1000000));

        int count = 0;
        // Real cameras use several chroma sampling schemes. Only the origin has an MCU constraint.
        for (int[] sampling : new int[][] { {2, 2}, {2, 1}, {1, 1} }) {
            byte[] jpeg = encode(source, sampling[0], sampling[1]);
            byte[] untouched = jpeg.clone();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            List<int[]> crops = new ArrayList<>();
            crops.add(new int[] {1, 1, 1279, 719});
            crops.add(new int[] {303, 155, 849, 706});
            crops.add(new int[] {0, 0, 410, 720});
            crops.add(new int[] {1272, 712, 1280, 720});
            List<String> references = Files.readAllLines(Paths.get(args[1]));
            for (String line : references.subList(1, references.size())) {
                String[] columns = line.split("\t");
                crops.add(Arrays.stream(columns[5].split(",")).mapToInt(Integer::parseInt).toArray());
            }
            for (int[] crop : crops) {
                int ax = crop[0] / (sampling[0] * 8) * (sampling[0] * 8);
                int ay = crop[1] / (sampling[1] * 8) * (sampling[1] * 8);
                byte[] output = NativeBridge.losslessCrop(jpeg, crop[0], crop[1],
                        crop[2] - crop[0], crop[3] - crop[1]);
                BufferedImage actual = ImageIO.read(new ByteArrayInputStream(output));
                check(actual.getWidth() == crop[2] - ax, "right edge was extended or clipped");
                check(actual.getHeight() == crop[3] - ay, "bottom edge was extended or clipped");
                // Ignore only chroma interpolation at the new image boundary. All interior
                // pixels must decode identically, with no rescale, re-encode or rotation.
                for (int y = 16; y < actual.getHeight() - 16; y++)
                    for (int x = 16; x < actual.getWidth() - 16; x++)
                        check(actual.getRGB(x, y) == decoded.getRGB(ax + x, ay + y),
                                "coefficient crop changed an interior source pixel");
                check(Arrays.equals(jpeg, untouched), "source JPEG bytes were changed");
                count++;
            }
            boolean rejected = false;
            try { NativeBridge.losslessCrop(jpeg, -1, 0, 100, 100); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "invalid rectangle was not rejected");
        }
        System.out.println("Native JNI lossless JPEG regression: " + count + " crops passed (4:2:0, 4:2:2, 4:4:4)");
    }

    private static byte[] encode(BufferedImage image, int horizontal, int vertical) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.95f);
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), param);
            String format = "javax_imageio_jpeg_image_1.0";
            IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree(format);
            org.w3c.dom.NodeList components = tree.getElementsByTagName("componentSpec");
            for (int i = 0; i < components.getLength(); i++) {
                IIOMetadataNode node = (IIOMetadataNode) components.item(i);
                node.setAttribute("HsamplingFactor", Integer.toString(i == 0 ? horizontal : 1));
                node.setAttribute("VsamplingFactor", Integer.toString(i == 0 ? vertical : 1));
            }
            metadata.setFromTree(format, tree);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally { writer.dispose(); }
        return output.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
