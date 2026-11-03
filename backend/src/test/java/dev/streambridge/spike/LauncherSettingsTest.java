package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSettingsTest {
    @TempDir Path root;
    @Test void missingSettingsAreNotMisreportedAsFfmpegExecutionFailure() {
        IOException failure = assertThrows(IOException.class,
                () -> LauncherSettings.environment(root.resolve("absent"), Map.of()));
        assertInstanceOf(NoSuchFileException.class, failure.getCause());
        assertTrue(failure.getMessage().contains(root.resolve("absent").toString()));
    }
    @Test void environmentOnlySetupRemainsSupportedWithDefaultPlaywrightCache() throws Exception {
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
        assertEquals(Path.of(browser).toString(),result.get("PLAYWRIGHT_BROWSERS_PATH"));
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
