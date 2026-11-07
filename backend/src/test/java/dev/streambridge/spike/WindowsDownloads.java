package dev.streambridge.spike;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShlObj;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** Current Windows known folder, not a guessed user.home/Downloads or a shell command. */
final class WindowsDownloads {
    interface NativeApi {
        int lookup(PointerByReference result);
        void free(Pointer allocation);
    }

    private WindowsDownloads() { }

    static Path path() throws IOException {
        // Lazy: portable builds/tests never load Windows DLLs.
        return path(new NativeApi() {
            @Override public int lookup(PointerByReference result) {
                // Do not create or verify a potentially redirected network directory here.
                // OutputFiles validates availability after native memory has been released.
                return Shell32.INSTANCE.SHGetKnownFolderPath(KnownFolders.FOLDERID_Downloads,
                        ShlObj.KNOWN_FOLDER_FLAG.DONT_VERIFY.getFlag(), null, result).intValue();
            }
            @Override public void free(Pointer allocation) { Ole32.INSTANCE.CoTaskMemFree(allocation); }
        });
    }

    static Path path(NativeApi api) throws IOException {
        try {
            var result = new PointerByReference();
            try {
                int status = api.lookup(result);
                if (status < 0) {
                    throw new IOException("DOWNLOADS_LOOKUP_FAILED HRESULT="
                            + String.format(Locale.ROOT, "0x%08X", status));
                }
                Pointer allocation = result.getValue();
                if (allocation == null) throw new IOException("DOWNLOADS_LOOKUP_EMPTY");
                String value = allocation.getWideString(0);
                if (value.isBlank()) throw new IOException("DOWNLOADS_LOOKUP_EMPTY");
                Path path = Path.of(value);
                if (!path.isAbsolute()) throw new IOException("DOWNLOADS_LOOKUP_INVALID_PATH");
                return path;
            } finally {
                // Windows requires freeing an allocated result even on a failed HRESULT.
                if (result.getValue() != null) api.free(result.getValue());
            }
        } catch (InvalidPathException invalid) {
            throw new IOException("DOWNLOADS_LOOKUP_INVALID_PATH", invalid);
        } catch (LinkageError unavailable) {
            throw new IOException("DOWNLOADS_LOOKUP_NATIVE_UNAVAILABLE", unavailable);
        }
    }
}
