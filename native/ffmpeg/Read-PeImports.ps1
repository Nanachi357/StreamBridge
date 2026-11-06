# Static, bounded reader for this recipe's AMD64 PE32+ files. Never loads a DLL.
# Format: https://learn.microsoft.com/en-us/windows/win32/debug/pe-format
if (-not ('StreamBridge.Materials.PeImports' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
namespace StreamBridge.Materials {
    public sealed class PeImports {
        private readonly byte[] data;
        private sealed class Section {
            public uint Va, FileSize, RawSize, Raw;
            public ulong VirtualEnd;
        }
        private readonly List<Section> sections = new List<Section>();
        private PeImports(byte[] bytes) { data = bytes; }
        private void Bounds(long offset, long size) {
            if (offset < 0 || size < 0 || offset > data.LongLength - size)
                throw new InvalidDataException("Invalid PE: out-of-bounds data");
        }
        private ushort U16(long offset) { Bounds(offset, 2); return BitConverter.ToUInt16(data, (int)offset); }
        private uint U32(long offset) { Bounds(offset, 4); return BitConverter.ToUInt32(data, (int)offset); }
        private long Map(ulong rva, uint size) {
            long result = -1;
            foreach (Section s in sections) {
                if (rva >= s.Va && rva + size <= (ulong)s.Va + s.FileSize) {
                    if (result != -1) throw new InvalidDataException("Invalid PE: ambiguous RVA");
                    result = (long)((ulong)s.Raw + rva - s.Va);
                }
            }
            if (result == -1) throw new InvalidDataException("Invalid PE: unmapped RVA");
            Bounds(result, size); return result;
        }
        private static bool PowerOfTwo(uint n) { return n != 0 && (n & (n - 1)) == 0; }
        private static ulong Align(ulong n, uint alignment) { return (n + alignment - 1) / alignment * alignment; }
        private void CheckExports(long optional, uint imageSize) {
            uint rva = U32(optional + 112), size = U32(optional + 116);
            if (rva == 0 && size == 0) return;
            if (rva == 0 || size < 40) throw new InvalidDataException("Invalid PE: export directory");
            long directory = Map(rva, size);
            uint count = U32(directory + 20), eat = U32(directory + 28);
            // MinGW emits an empty export directory in the executables.
            if (count == 0) {
                if (U32(directory + 24) != 0)
                    throw new InvalidDataException("Invalid PE: names without export addresses");
                return;
            }
            if (count > 65536 || eat == 0)
                throw new InvalidDataException("Invalid PE: export address table");
            long entries = Map(eat, count * 4);
            for (uint i = 0; i < count; i++) {
                uint target = U32(entries + i * 4);
                // Scan every entry, including ordinal-only exports. This recipe
                // has no forwarders; reject them without resolving/loading DLLs.
                if (target >= rva && (ulong)target < (ulong)rva + size)
                    throw new InvalidDataException("Invalid PE: forwarded exports are unsupported");
                if (target >= imageSize) throw new InvalidDataException("Invalid PE: export target outside image");
            }
        }
        private string Name(uint rva) {
            var name = new StringBuilder();
            for (uint i = 0; i < 256; i++) {
                byte value = data[(int)Map((ulong)rva + i, 1)];
                if (value == 0) {
                    string text = name.ToString();
                    if (!Regex.IsMatch(text, @"\A[A-Za-z0-9_.-]+\.dll\z", RegexOptions.IgnoreCase))
                        throw new InvalidDataException("Invalid PE: invalid import name");
                    return text;
                }
                if (value > 127) throw new InvalidDataException("Invalid PE: non-ASCII import name");
                name.Append((char)value);
            }
            throw new InvalidDataException("Invalid PE: unterminated import name");
        }
        private string[] Read() {
            if (U16(0) != 0x5a4d) throw new InvalidDataException("Invalid PE: missing MZ");
            long pe = U32(0x3c);
            if (U32(pe) != 0x4550 || U16(pe + 4) != 0x8664)
                throw new InvalidDataException("Invalid PE: expected AMD64 image");
            int count = U16(pe + 6), optionalSize = U16(pe + 20);
            long optional = pe + 24;
            if (count < 1 || count > 96 || optionalSize < 240 || U16(optional) != 0x20b)
                throw new InvalidDataException("Invalid PE: unsupported headers");
            Bounds(optional, optionalSize);
            if (U32(optional + 108) != 16) throw new InvalidDataException("Invalid PE: unsupported directories");
            // This recipe does not use bound or delayed imports. Fail closed if introduced.
            foreach (int index in new int[] { 11, 13 }) {
                if (U32(optional + 112 + index * 8) != 0 || U32(optional + 116 + index * 8) != 0)
                    throw new InvalidDataException("Invalid PE: bound/delay imports are unsupported");
            }
            long table = optional + optionalSize;
            Bounds(table, count * 40);
            uint sectionAlignment = U32(optional + 32), fileAlignment = U32(optional + 36);
            uint imageSize = U32(optional + 56), headersSize = U32(optional + 60);
            // Narrow profile: ordinary page-aligned images, not low-alignment PEs.
            if (!PowerOfTwo(sectionAlignment) || sectionAlignment < 4096 ||
                !PowerOfTwo(fileAlignment) || fileAlignment < 512 || fileAlignment > 65536 ||
                fileAlignment > sectionAlignment || imageSize == 0 || imageSize % sectionAlignment != 0 ||
                headersSize < table + count * 40 || headersSize % fileAlignment != 0)
                throw new InvalidDataException("Invalid PE: image alignment or headers");
            Bounds(0, headersSize);
            ulong headersEnd = Align(headersSize, sectionAlignment);
            if (headersEnd > imageSize) throw new InvalidDataException("Invalid PE: headers outside image");
            for (int i = 0; i < count; i++) {
                long section = table + i * 40;
                uint virtualSize = U32(section + 8), va = U32(section + 12);
                uint rawSize = U32(section + 16), raw = U32(section + 20);
                Bounds(raw, rawSize);
                ulong extent = Math.Max(virtualSize, rawSize);
                ulong end = Align((ulong)va + extent, sectionAlignment);
                if (extent == 0 || va % sectionAlignment != 0 || va < headersEnd || end > imageSize ||
                    rawSize % fileAlignment != 0 || (rawSize != 0 && (raw < headersSize || raw % fileAlignment != 0)))
                    throw new InvalidDataException("Invalid PE: section outside image or misaligned");
                foreach (Section s in sections) {
                    if ((ulong)va < s.VirtualEnd && (ulong)s.Va < end)
                        throw new InvalidDataException("Invalid PE: overlapping virtual sections");
                    if (rawSize > 0 && s.RawSize > 0 && (ulong)raw < (ulong)s.Raw + s.RawSize && (ulong)s.Raw < (ulong)raw + rawSize)
                        throw new InvalidDataException("Invalid PE: overlapping file sections");
                }
                sections.Add(new Section { Va = va, FileSize = virtualSize == 0 ? rawSize : Math.Min(rawSize, virtualSize), RawSize = rawSize, Raw = raw, VirtualEnd = end });
            }
            CheckExports(optional, imageSize);
            uint imports = U32(optional + 120), size = U32(optional + 124);
            if (imports == 0 || size < 20) throw new InvalidDataException("Invalid PE: missing imports");
            Map(imports, size); // Validate the whole declared range, not just its first descriptors.
            var names = new List<string>();
            var unique = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            for (uint i = 0; i < 256 && (ulong)(i + 1) * 20 <= size; i++) {
                long entry = Map((ulong)imports + i * 20, 20);
                bool empty = true;
                for (int j = 0; j < 20; j += 4) empty &= U32(entry + j) == 0;
                if (empty) {
                    if (names.Count == 0) throw new InvalidDataException("Invalid PE: empty imports");
                    return names.ToArray();
                }
                if (U32(entry + 12) == 0 || U32(entry + 16) == 0)
                    throw new InvalidDataException("Invalid PE: incomplete import descriptor");
                string name = Name(U32(entry + 12));
                if (!unique.Add(name)) throw new InvalidDataException("Invalid PE: duplicate import");
                names.Add(name);
            }
            throw new InvalidDataException("Invalid PE: unterminated import table");
        }
        public static string[] FromFile(string path) {
            using (var file = File.OpenRead(path)) {
                if (file.Length < 64 || file.Length > 64 * 1024 * 1024)
                    throw new InvalidDataException("Invalid PE: unsupported file size");
                using (var reader = new BinaryReader(file)) {
                    byte[] bytes = reader.ReadBytes((int)file.Length);
                    return new PeImports(bytes).Read();
                }
            }
        }
    }
}
'@
}
