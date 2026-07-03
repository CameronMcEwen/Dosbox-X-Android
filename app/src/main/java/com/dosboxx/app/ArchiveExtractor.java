package com.dosboxx.app;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts game/CD archives for the in-app importer.
 *
 * Two install shapes:
 *  - DOS game: every file extracted into a game folder, with a redundant single
 *    top-level wrapper directory flattened away.
 *  - CD-ROM:   only the disc-image files (.iso / .cue / .bin / .img / .ccd / .sub / .chd / .mdf) extracted
 *    flat into the CD library.
 *
 * Supported container formats (dispatched by extension onto a common Source):
 *  - .zip  via java.util.zip (no extra dependency)
 *  - .7z   via Apache Commons Compress SevenZFile (+ Tukaani XZ for LZMA)
 *  - .rar  via junrar (RAR4 only; RAR5 is detected and rejected with a clear
 *          message — see UnsupportedFormatException)
 */
final class ArchiveExtractor {

    /** Progress callback (cumulative bytes written / total, total<0 = unknown). */
    interface Progress { void onProgress(long done, long total); }

    /** True for an archive we can read (.zip / .7z / .rar). */
    static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar");
    }

    /** The archive extension of name (lower-cased, with dot), or "" if none. */
    static String archiveExt(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".zip")) return ".zip";
        if (n.endsWith(".7z"))  return ".7z";
        if (n.endsWith(".rar")) return ".rar";
        return "";
    }

    /** Archive basename with its container extension stripped (case-insensitive). */
    static String archiveBaseName(String name) {
        String e = archiveExt(name);
        return e.isEmpty() ? name : name.substring(0, name.length() - e.length());
    }

    /**
     * Raised when an archive is a flavour we can read structurally but not
     * decompress (currently only RAR5). Carries a user-facing message so the
     * importer can surface it instead of a generic "couldn't prepare" toast.
     */
    static final class UnsupportedFormatException extends RuntimeException {
        UnsupportedFormatException(String msg) { super(msg); }
    }

    // ---- format-agnostic source abstraction ----

    /** One archive entry, common across all formats. */
    static final class Entry {
        final String name;
        final boolean directory;
        final long size;        // uncompressed bytes, -1 if unknown
        final Object handle;    // format-specific entry object for open()
        Entry(String name, boolean directory, long size, Object handle) {
            this.name = name; this.directory = directory; this.size = size; this.handle = handle;
        }
    }

    /** A readable archive: enumerate entries and open content streams. */
    abstract static class Source implements Closeable {
        abstract List<Entry> entries() throws IOException;
        abstract InputStream open(Entry e) throws IOException;
        long size(Entry e) { return e.size; }
    }

    /** Open the right Source for an archive file, by extension. */
    static Source sourceFor(File archive) throws IOException {
        String n = archive.getName().toLowerCase(Locale.US);
        if (n.endsWith(".zip")) return new ZipSource(archive);
        if (n.endsWith(".7z"))  return new SevenZSource(archive);
        if (n.endsWith(".rar")) return RarSource.forFile(archive);   // may throw UnsupportedFormatException (RAR5)
        throw new IOException("not a supported archive: " + archive.getName());
    }

    // ---- zip ----

    private static final class ZipSource extends Source {
        private final ZipFile zf;
        ZipSource(File f) throws IOException { zf = new ZipFile(f); }
        public List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                out.add(new Entry(e.getName(), e.isDirectory(), e.getSize(), e));
            }
            return out;
        }
        public InputStream open(Entry ent) throws IOException {
            return zf.getInputStream((ZipEntry) ent.handle);
        }
        public void close() throws IOException { zf.close(); }
    }

    // ---- 7z ----

    private static final class SevenZSource extends Source {
        private final SevenZFile sz;
        SevenZSource(File f) throws IOException { sz = SevenZFile.builder().setFile(f).get(); }
        public List<Entry> entries() throws IOException {
            List<Entry> out = new ArrayList<>();
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                out.add(new Entry(e.getName(), e.isDirectory(), e.getSize(), e));
            }
            return out;
        }
        public InputStream open(Entry ent) throws IOException {
            return sz.getInputStream((SevenZArchiveEntry) ent.handle);
        }
        public void close() throws IOException { sz.close(); }
    }

    // ---- rar (RAR4 via junrar; RAR5 rejected) ----

    private static final class RarSource extends Source {
        private final Archive archive;
        private RarSource(File f) throws IOException {
            try { archive = new Archive(f); }
            catch (RarException e) { throw new IOException("RAR read error: " + e.getMessage(), e); }
        }
        /** Construct, but first sniff the signature so RAR5 is reported clearly. */
        static RarSource forFile(File f) throws IOException {
            if (isRar5(f)) throw new UnsupportedFormatException(
                "RAR5 archives aren't supported — re-pack as .7z or .zip.");
            return new RarSource(f);
        }
        public List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            for (FileHeader fh : archive) {
                out.add(new Entry(fh.getFileNameString(), fh.isDirectory(), fh.getDataSize(), fh));
            }
            return out;
        }
        public InputStream open(Entry ent) throws IOException {
            return archive.getInputStream((FileHeader) ent.handle);
        }
        public void close() throws IOException { archive.close(); }
    }

    /** RAR4 signature is 52 61 72 21 1A 07 00; RAR5 is 52 61 72 21 1A 07 01 00. */
    private static boolean isRar5(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[8];
            int got = 0;
            while (got < 8) {
                int r = in.read(h, got, 8 - got);
                if (r < 0) break;
                got += r;
            }
            return got >= 7
                && (h[0] & 0xFF) == 0x52 && (h[1] & 0xFF) == 0x61 && (h[2] & 0xFF) == 0x72
                && (h[3] & 0xFF) == 0x21 && (h[4] & 0xFF) == 0x1A && (h[5] & 0xFF) == 0x07
                && (h[6] & 0xFF) == 0x01;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ---- disc-image / DOS-program classification ----

    private static boolean isDiscImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".iso") || n.endsWith(".cue") || n.endsWith(".bin")
            || n.endsWith(".img") || n.endsWith(".ccd") || n.endsWith(".sub")
            || n.endsWith(".chd")
            || n.endsWith(".mdf") || n.endsWith(".gog") || n.endsWith(".ins")
            || n.endsWith(".inst");
    }

    private static boolean isDosProgram(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".exe") || n.endsWith(".bat") || n.endsWith(".com");
    }

    /** What an archive looks like, so the importer can suggest a destination. */
    static final class Kind {
        boolean hasDiscImage;
        boolean hasDosProgram;
    }

    /** Peek at the entry names without extracting. */
    static Kind classify(File archive) {
        Kind k = new Kind();
        try {
            for (String name : listNames(archive)) {
                if (isDiscImage(name)) k.hasDiscImage = true;
                else if (isDosProgram(name)) k.hasDosProgram = true;
            }
        } catch (IOException ignored) { }
        return k;
    }

    private static List<String> listNames(File archive) throws IOException {
        List<String> out = new ArrayList<>();
        try (Source s = sourceFor(archive)) {
            for (Entry e : s.entries()) if (!e.directory) out.add(e.name);
        }
        return out;
    }

    /**
     * Extract a DOS game into destDir (a fresh game folder). A single common
     * top-level directory in the archive is stripped so files land directly in
     * destDir. Returns false on any error.
     */
    static boolean extractGame(File archive, File destDir, Progress p) {
        String strip = commonTopDir(archive);
        return extract(archive, destDir, false, strip, totalBytes(archive, false), p);
    }

    /** Extract only the disc-image files (flat, basename only) into destDir. */
    static boolean extractDiscImages(File archive, File destDir, Progress p) {
        return extract(archive, destDir, true, null, totalBytes(archive, true), p);
    }

    /** Sum of the uncompressed sizes of the entries we will write (-1 if unknown). */
    private static long totalBytes(File archive, boolean discOnly) {
        long total = 0;
        try (Source s = sourceFor(archive)) {
            for (Entry e : s.entries()) {
                if (e.directory) continue;
                if (discOnly && !isDiscImage(e.name)) continue;
                long sz = s.size(e);
                if (sz < 0) return -1;
                total += sz;
            }
        } catch (IOException e) {
            return -1;
        }
        return total;
    }

    /** Single common top-level folder shared by every entry, or null. */
    private static String commonTopDir(File archive) {
        try {
            String top = null;
            for (String name : listNames(archive)) {
                String n = name.replace('\\', '/');
                int slash = n.indexOf('/');
                if (slash < 0) return null;            // a file at the root → no common dir
                String first = n.substring(0, slash);
                if (top == null) top = first;
                else if (!top.equals(first)) return null;
            }
            return top;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean extract(File archive, File destDir, boolean discOnly,
                                   String stripTop, long total, Progress p) {
        if (!destDir.exists()) destDir.mkdirs();
        long[] done = {0};
        long[] lastReport = {-1};
        try (Source s = sourceFor(archive)) {
            for (Entry e : s.entries()) {
                if (e.directory) continue;
                File out = target(e.name, destDir, discOnly, stripTop);
                if (out == null) continue;
                InputStream in = s.open(e);
                try { writeStream(in, out, total, done, lastReport, p); }
                finally { in.close(); }
            }
            if (p != null) p.onProgress(done[0], total);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Report progress at most once per ~2MB to avoid flooding the UI thread. */
    private static void report(long total, long[] done, long[] lastReport, int n, Progress p) {
        done[0] += n;
        if (p != null && (done[0] - lastReport[0] >= (2L << 20) || lastReport[0] < 0)) {
            lastReport[0] = done[0];
            p.onProgress(done[0], total);
        }
    }

    /** Resolve the output file for an entry, or null to skip it. */
    private static File target(String name, File destDir, boolean discOnly, String stripTop) {
        String n = name.replace('\\', '/');
        if (n.contains("..")) return null;
        if (discOnly) {
            if (!isDiscImage(n)) return null;
            int slash = n.lastIndexOf('/');
            return new File(destDir, slash >= 0 ? n.substring(slash + 1) : n);   // flat
        }
        if (stripTop != null && (n.equals(stripTop) || n.startsWith(stripTop + "/"))) {
            n = n.substring(stripTop.length());
            while (n.startsWith("/")) n = n.substring(1);
        }
        if (n.isEmpty()) return null;
        File out = new File(destDir, n);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return out;
    }

    private static void writeStream(InputStream in, File out, long total,
                                    long[] done, long[] lastReport, Progress p) throws IOException {
        OutputStream o = new FileOutputStream(out);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); report(total, done, lastReport, n, p); }
        } finally {
            o.close();
        }
    }

    private ArchiveExtractor() { }
}