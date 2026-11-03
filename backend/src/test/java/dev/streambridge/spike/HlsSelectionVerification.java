package dev.streambridge.spike;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Independent output-content oracle for the SYNTHETIC fixture, not a general media analyzer. */
final class HlsSelectionVerification {
    static void verify(Path ffmpeg, Path ffprobe, Path video, Path directory, HlsMaster.Selection selection)
            throws IOException, InterruptedException {
        Path probe = directory.resolve("probe.log");
        HlsDownloadSpike.runChecked(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                "stream=codec_name,codec_type,width,height,sample_rate,channels:format=duration", "-of", "default=noprint_wrappers=1",
                video.toString()), probe, 30_000);
        List<String> lines = Files.readAllLines(probe);
        double duration = Double.parseDouble(lines.stream().filter(l -> l.startsWith("duration=")).findFirst().orElseThrow().substring(9));
        check(lines.stream().filter(l -> l.startsWith("codec_type=")).count() == 2
                && lines.containsAll(List.of("codec_type=video", "codec_name=h264", "codec_type=audio", "codec_name=aac",
                "sample_rate=48000", "channels=1", "width=" + selection.video().width(), "height=" + selection.video().height()))
                && Double.isFinite(duration) && Math.abs(duration - 8) <= 0.1, "Wrong tracks, resolution or duration");
        HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                "-err_detect", "explode", "-i", video.toString(), "-map", "0", "-f", "null", "-"), directory.resolve("decode.log"), 30_000);
        HlsDownloadSpike.verifySynchronization(ffmpeg, video, directory, 30_000);

        // Clip A has a red corner; clip B a blue corner, even when resolutions/durations match.
        String clip = selection.master().getPath().split("/")[1];
        check(clip.equals("a") || clip.equals("b"), "Unknown fixture clip");
        Path pixel = directory.resolve("corner.rgb");
        HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-n",
                "-i", video.toString(), "-ss", "1", "-vf", "crop=8:8:0:0,scale=1:1", "-frames:v", "1",
                "-an", "-pix_fmt", "rgb24", "-f", "rawvideo", pixel.toString()), directory.resolve("corner.log"), 30_000);
        byte[] rgb = Files.readAllBytes(pixel);
        check(rgb.length == 3, "Missing color marker");
        int r = Byte.toUnsignedInt(rgb[0]), g = Byte.toUnsignedInt(rgb[1]), b = Byte.toUnsignedInt(rgb[2]);
        check(clip.equals("a") ? r > 200 && g < 40 && b < 40 : b > 200 && r < 40 && g < 40, "Wrong clip's video content");

        // Decode PCM and measure actual beep frequencies, rather than trusting track names or command arguments.
        String language = selection.audio().language();
        check(language.equals("uk") || language.equals("en"), "Unknown fixture audio label");
        int expectedHz = (clip.equals("a") ? 0 : 880) + (language.equals("uk") ? 440 : 880);
        Path pcm = directory.resolve("audio.pcm");
        HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-n",
                "-i", video.toString(), "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-c:a", "pcm_s16le",
                "-f", "s16le", pcm.toString()), directory.resolve("audio.log"), 30_000);
        byte[] samples = Files.readAllBytes(pcm);
        check(samples.length >= 8 * 48000 * 2 && samples.length < 9 * 48000 * 2, "Unexpected PCM length");
        var buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        for (int second : new int[]{2, 4, 6}) {
            int begin = (int) ((second + 0.05) * 48000), end = begin + 2400;
            int crossings = 0;
            double sumSquares = 0;
            for (int i = begin; i < end; i++) {
                short sample = buffer.getShort(i * 2);
                if (sample > 0 && buffer.getShort((i - 1) * 2) <= 0) crossings++;
                sumSquares += (double) sample * sample;
            }
            double hz = crossings / 0.05;
            check(Math.sqrt(sumSquares / 2400) > 1000 && Math.abs(hz - expectedHz) <= 25, "Wrong/silent audio at " + second + "s: " + hz + "Hz");
            System.out.printf(Locale.ROOT, "PASS: actual audio at %ds = %.0f Hz, expected %d Hz%n", second, hz, expectedHz);
        }
        Files.writeString(directory.resolve("verified.txt"), "clip=" + clip + "\nresolution=" + selection.video().width() + "x"
                + selection.video().height() + "\naudio=" + language + "\nfrequency=" + expectedHz + "\nduration=" + duration + "\n");
        System.out.println("PASS: actual clip " + clip + ", corner RGB=" + r + "," + g + "," + b + "; resolution matches selection.");
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
