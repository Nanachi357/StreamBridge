package dev.streambridge.spike;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WindowsDownloadsTest {
    @TempDir Path root;

    @Test void preservesRedirectedUnicodePathAndReleasesNativeMemory() throws Exception {
        Path relocated = Files.createDirectory(root.resolve("Інші завантаження 日本語"));
        var api = new Result(relocated.toString(), 0);
        assertEquals(relocated, WindowsDownloads.path(api));
        assertEquals(1, api.frees);
    }

    @Test void doesNotCreateMissingFolderOrSubstituteADefault() throws Exception {
        Path missing = root.resolve("relocated-but-unavailable");
        var api = new Result(missing.toString(), 0);
        Path result = WindowsDownloads.path(api);
        assertEquals(missing, result);
        assertFalse(Files.exists(result));
        assertThrows(IOException.class, () -> OutputFiles.checkedDirectory(result));
        assertEquals(1, api.frees);
    }

    @Test void reportsHresultAndFreesEvenAFailureAllocation() {
        var api = new Result(root.toString(), 0x80070005);
        IOException error = assertThrows(IOException.class, () -> WindowsDownloads.path(api));
        assertEquals("DOWNLOADS_LOOKUP_FAILED HRESULT=0x80070005", error.getMessage());
        assertEquals(1, api.frees);
    }

    @Test void failedLookupWithoutAllocationNeedsNoFree() {
        var api = new Result(null, 0x80070002);
        assertEquals("DOWNLOADS_LOOKUP_FAILED HRESULT=0x80070002",
                assertThrows(IOException.class, () -> WindowsDownloads.path(api)).getMessage());
        assertEquals(0, api.frees);
    }

    @Test void rejectsNullOrEmptySuccessfulResultsWithoutGuessingAPath() {
        for (String value : new String[]{null, "", "   "}) {
            var api = new Result(value, 0);
            assertEquals("DOWNLOADS_LOOKUP_EMPTY",
                    assertThrows(IOException.class, () -> WindowsDownloads.path(api)).getMessage());
            assertEquals(value == null ? 0 : 1, api.frees);
        }
    }

    @Test void rejectsRelativeResultAndStillFreesIt() {
        var api = new Result("relative/Downloads", 0);
        assertEquals("DOWNLOADS_LOOKUP_INVALID_PATH",
                assertThrows(IOException.class, () -> WindowsDownloads.path(api)).getMessage());
        assertEquals(1, api.frees);
    }

    @Test void unavailableNativeLibraryHasAnExplicitDiagnosticNotAShellFallback() {
        var missingLibrary = new UnsatisfiedLinkError("fixture: unavailable native symbol");
        var api = new Result(null, 0) {
            @Override public int lookup(PointerByReference output) { throw missingLibrary; }
        };
        IOException error = assertThrows(IOException.class, () -> WindowsDownloads.path(api));
        assertEquals("DOWNLOADS_LOOKUP_NATIVE_UNAVAILABLE", error.getMessage());
        assertSame(missingLibrary, error.getCause());
        assertEquals(0, api.frees);
    }

    @Test void exceptionAfterAllocationStillReleasesIt() {
        var api = new Result(root.toString(), 0) {
            @Override public int lookup(PointerByReference output) {
                super.lookup(output);
                throw new UnsatisfiedLinkError("fixture: native failure after allocation");
            }
        };
        assertThrows(IOException.class, () -> WindowsDownloads.path(api));
        assertEquals(1, api.frees);
    }

    private static class Result implements WindowsDownloads.NativeApi {
        private final String value;
        private final int status;
        private Memory allocation;
        int frees;
        Result(String value, int status) { this.value = value; this.status = status; }
        @Override public int lookup(PointerByReference output) {
            if (value != null) {
                allocation = new Memory((long) (value.length() + 1) * Native.WCHAR_SIZE);
                allocation.setWideString(0, value);
                output.setValue(allocation);
            }
            return status;
        }
        @Override public void free(Pointer pointer) {
            assertEquals(allocation, pointer);
            frees++;
            allocation.close();
        }
    }
}
