package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSettingsTest {
    @TempDir Path root;
    private Path packagedMedia() throws Exception {
        Path directory = Files.createDirectories(root.resolve("native/ffmpeg"));
        for (String name : java.util.List.of("ffmpeg.exe", "ffprobe.exe", "avcodec-63.dll", "avfilter-12.dll",
                "avformat-63.dll", "avutil-61.dll", "swresample-7.dll", "swscale-10.dll"))
            Files.writeString(directory.resolve(name), "fixture");
        return directory;
    }
    @Test void installedMediaIgnoresMissingOrMalformedPersonalSettingsAndExternalOverride() throws Exception {
        Path jar = Files.writeString(root.resolve("streambridge.jar"), "fixture");
        Path media = packagedMedia();
        Path settings = root.resolve("tools.properties");
        for (boolean malformed : new boolean[]{false, true}) {
            if (malformed) Files.write(settings, new byte[]{(byte)0xc3, (byte)0x28});
            var result = LauncherSettings.environment(settings, Map.of("STREAMBRIDGE_FFMPEG_DIR", "wrong"), jar);
            assertEquals(media.toString(), result.get("STREAMBRIDGE_FFMPEG_DIR"));
            assertEquals("1", result.get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
        }
    }
    @Test void missingInstalledDllDoesNotFallBackToExternalTools() throws Exception {
        Path jar = Files.writeString(root.resolve("streambridge.jar"), "fixture");
        Path media = packagedMedia();
        Files.delete(media.resolve("avcodec-63.dll"));
        var failure = assertThrows(IOException.class, () -> LauncherSettings.environment(root.resolve("absent"),
                Map.of("STREAMBRIDGE_FFMPEG_DIR", root.toString()), jar));
        assertTrue(failure.getMessage().contains("avcodec-63.dll"));
    }
    @Test void requiredMarkerDetectsEntirelyMissingMediaDirectory() throws Exception {
        Path jar = Files.writeString(root.resolve("streambridge.jar"), "fixture");
        Files.writeString(root.resolve("bundled-media.required"), "required");
        assertThrows(IOException.class, () -> LauncherSettings.environment(root.resolve("absent"),
                Map.of("STREAMBRIDGE_FFMPEG_DIR", root.toString()), jar));
    }
    @Test void developmentClassesDoNotOptIntoAdjacentMedia() throws Exception {
        packagedMedia();
        String external = root.resolve("external").toString();
        assertEquals(external, LauncherSettings.environment(root.resolve("absent"),
                Map.of("STREAMBRIDGE_FFMPEG_DIR", external), root).get("STREAMBRIDGE_FFMPEG_DIR"));
    }
    @Test void missingSettingsAreNotMisreportedAsFfmpegExecutionFailure() {
        IOException failure = assertThrows(IOException.class,
                () -> LauncherSettings.environment(root.resolve("absent"), Map.of()));
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(root.resolve("absent").toString()));
    }
    @Test void environmentOnlyMediaSetupRemainsSupported() throws Exception {
        String media = root.resolve("media").toString();
        assertEquals(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1", "STREAMBRIDGE_FFMPEG_DIR", media),
                LauncherSettings.environment(root.resolve("absent"), Map.of("STREAMBRIDGE_FFMPEG_DIR", media)));
    }
    @Test void existingButUnreadableAsTextSettingsDoNotFallBackSilently() throws Exception {
        Path file=root.resolve("malformed.properties");
        Files.write(file,new byte[]{(byte)0xc3, (byte)0x28});
        IOException failure=assertThrows(IOException.class,()->LauncherSettings.environment(file,
                Map.of("STREAMBRIDGE_FFMPEG_DIR",root.toString())));
        assertInstanceOf(java.nio.charset.MalformedInputException.class,failure.getCause());
        assertTrue(failure.getMessage().contains(file.toString()));
    }
    @Test void missingMediaSettingIsReportedBeforeStartingProbe() throws Exception {
        Path file=root.resolve("empty.properties"); Files.writeString(file,"# no tool paths\n");
        IOException failure=assertThrows(IOException.class,()->LauncherSettings.environment(file,Map.of()));
        assertTrue(failure.getMessage().contains("mediaToolsDirectory"));
    }
    @Test void environmentOverridesLocalSettingsAndUnrelatedKeysAreNotExported() throws Exception {
        Path file = root.resolve("tools.properties");
        String tools = root.resolve("tools with spaces").toString().replace('\\','/');
        String browser = root.resolve("chromium").toString().replace('\\','/');
        String override = root.resolve("override").toString();
        Files.writeString(file,"mediaToolsDirectory="+tools+"\nchromiumDirectory="+browser+"\nJAVA_HOME=untrusted\n");
        var result=LauncherSettings.environment(file,Map.of("STREAMBRIDGE_FFMPEG_DIR",override));
        assertEquals(override,result.get("STREAMBRIDGE_FFMPEG_DIR"));
        assertFalse(result.containsKey("PLAYWRIGHT_BROWSERS_PATH")); // Old browser setting is ignored.
        assertFalse(result.containsKey("JAVA_HOME"));
    }
    @Test void relativePathsAreRejectedRatherThanDependingOnWorkingDirectory() throws Exception {
        Path file=root.resolve("tools.properties");Files.writeString(file,"mediaToolsDirectory=relative/bin\n");
        assertThrows(IOException.class,()->LauncherSettings.environment(file,Map.of()));
    }
    @Test void childAlwaysUsesCurrentRuntimeNotJavaHomeOrPath() throws Exception {
        String media = root.resolve("fixture-media").toString();
        var builder=LauncherSettings.childJava(LauncherDependencyProbe.class, root.resolve("absent.properties"),
                Map.of("STREAMBRIDGE_FFMPEG_DIR", media, "JAVA_HOME", root.resolve("wrong-java").toString()));
        assertTrue(Path.of(builder.command().getFirst()).startsWith(Path.of(System.getProperty("java.home"))));
        assertEquals(LauncherDependencyProbe.class.getName(),builder.command().getLast());
        assertEquals("1",builder.environment().get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
        assertEquals(media,builder.environment().get("STREAMBRIDGE_FFMPEG_DIR"));
    }
}
