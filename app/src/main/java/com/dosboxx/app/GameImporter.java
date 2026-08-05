package com.dosboxx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guided DOS / Windows 98 game import via the system file picker (SAF
 * ACTION_OPEN_DOCUMENT and ACTION_OPEN_DOCUMENT_TREE).
 */
final class GameImporter {

    public static int sAddPlatform = 0;

    public static final int KIND_DOS_GAME      = 0;
    public static final int KIND_DOS_CD        = 1;
    public static final int KIND_DOS_CD_SETUP  = 2;
    public static final int KIND_WIN98_MEDIA   = 3;

    public static final int REQ_PICK = 4242;
    public static final int REQ_PICK_CD_GAME = 4243;
    public static final int REQ_PICK_RIP_GAME = 4244;
    public static final int REQ_PICK_FOLDER = 4245;

    private GameImporter() { }

    public static void startSafPicker(Activity a, int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip",
            "application/octet-stream",
            "application/x-cd-image",
            "application/x-iso9660-image",
            "*/*"
        });
        a.startActivityForResult(i, requestCode);
    }

    public static void startFolderPicker(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        a.startActivityForResult(i, REQ_PICK_FOLDER);
    }

    public static void onPickResult(final Activity a, final Intent data,
                                    final GameLauncherActivity host,
                                    final int requestCode) {
        if (data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQ_PICK_FOLDER) {
            onFolderPickResult(a, uri, host, data);
            return;
        }

        String name = queryDisplayName(a, uri);
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "game";
        final String base = stripExt(name);
        final String ext = extOf(name);

        if (requestCode == REQ_PICK_CD_GAME) {
            if (!isCdMediaName(name)) {
                Toast.makeText(a, "CD games need .iso, .cue, .bin, .img, or .zip media.", Toast.LENGTH_LONG).show();
                return;
            }
            importSafUri(a, host, uri, base, ext, KIND_DOS_CD);
            return;
        }

        if (requestCode == REQ_PICK_RIP_GAME) {
            final Runnable dos = () -> importSafUri(a, host, uri, base, ext, KIND_DOS_GAME);
            final Runnable win = () -> {
                if (!ext.equalsIgnoreCase("zip")) {
                    Toast.makeText(a, "Windows 98 rip import needs a .zip so it can be mounted as a CD.", Toast.LENGTH_LONG).show();
                    return;
                }
                importSafUri(a, host, uri, base, ext, KIND_WIN98_MEDIA);
            };
            int plat = sAddPlatform; sAddPlatform = 0;
            if (plat == 1)      dos.run();
            else if (plat == 2) win.run();
            else                promptPlatform(a, "Add rip game", base, dos, win);
            return;
        }

        final int guessedKind = kindForName(name);
        final String[] options;
        final int[] kinds;
        if (guessedKind == KIND_DOS_CD) {
            options = new String[]{"Setup this CD in Windows 98", "Setup this CD in MS-DOS", "Add to CD library only", "Add as MS-DOS rip folder"};
            kinds   = new int[]{KIND_WIN98_MEDIA, KIND_DOS_CD_SETUP, KIND_DOS_CD, KIND_DOS_GAME};
        } else {
            options = new String[]{"Install as MS-DOS rip", "Use as Windows 98 setup media", "Add to CD library"};
            kinds   = new int[]{KIND_DOS_GAME, KIND_WIN98_MEDIA, KIND_DOS_CD};
        }
        new AlertDialog.Builder(a)
            .setTitle("Add " + base)
            .setItems(options, (d, w) -> importSafUri(a, host, uri, base, ext, kinds[w]))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void onFolderPickResult(Activity a, Uri uri, GameLauncherActivity host, Intent data) {
        Log.d("DosBoxX", "onFolderPickResult: uri=" + uri);
        try {
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            //noinspection WrongConstant
            a.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception e) {
            Log.e("DosBoxX", "Failed to take persistable URI permission", e);
        }

        DocumentFile root = DocumentFile.fromTreeUri(a, uri);
        if (root == null || !root.isDirectory()) {
            Log.e("DosBoxX", "onFolderPickResult: root is null or not a directory");
            Toast.makeText(a, "Invalid folder selected.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = root.getName();
        Log.d("DosBoxX", "onFolderPickResult: name=" + name);
        if (name == null || name.isEmpty() || name.equals("primary") || name.equals("home")) {
            // Try to get a better name from the URI if root.getName() is generic
            name = uri.getLastPathSegment();
            if (name != null && name.contains(":")) {
                name = name.substring(name.lastIndexOf(':') + 1);
            }
        }
        if (name == null || name.isEmpty()) name = "Imported Folder";
        
        showImportWizard(a, host, null, name, KIND_DOS_GAME, uri);
    }

    private static void promptPlatform(final Activity a, String title, String base,
                                       final Runnable dos, final Runnable win98) {
        new AlertDialog.Builder(a)
            .setTitle(title + ": " + base)
            .setItems(new String[]{"MS-DOS", "Windows 98"}, (d, w) -> {
                if (w == 0) dos.run();
                else        win98.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void importSafUri(final Activity a, final GameLauncherActivity host,
                                     final Uri uri, final String base, final String ext,
                                     final int kind) {
        final String safe = safeName(base);
        final boolean archiveMedia = (kind == KIND_DOS_CD || kind == KIND_DOS_CD_SETUP || kind == KIND_WIN98_MEDIA) && ext.equalsIgnoreCase("zip");
        final File destDir, destFile;
        if (archiveMedia) {
            destDir = host.getCdArchivesDir();
            destFile = new File(destDir, safe + "." + ext);
        } else if (kind == KIND_DOS_CD || kind == KIND_DOS_CD_SETUP || kind == KIND_WIN98_MEDIA) {
            destDir = host.getCdsDir();
            destFile = new File(destDir, safe + "." + ext);
        } else {
            destDir = host.getImportDir();
            destFile = new File(destDir, safe + "." + ext);
        }
        if (!destDir.isDirectory()) destDir.mkdirs();
        if (destFile.exists()) destFile.delete();

        final AlertDialog dlg = makeProgressDialog(a, "Copying " + base + "." + ext + "…");
        dlg.show();

        new Thread(() -> {
            boolean copied = false;
            try {
                ContentResolver cr = a.getContentResolver();
                try (InputStream in = cr.openInputStream(uri);
                     OutputStream out = new FileOutputStream(destFile)) {
                    if (in == null) throw new Exception("couldn't open picked file");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    copied = true;
                }
            } catch (Exception ignored) { }
            final boolean fCopied = copied;
            a.runOnUiThread(() -> {
                dlg.dismiss();
                if (!fCopied) {
                    destFile.delete();
                    Toast.makeText(a, "Couldn't copy " + base + ".", Toast.LENGTH_LONG).show();
                    return;
                }
                if (kind == KIND_DOS_GAME || kind == KIND_DOS_CD_SETUP || kind == KIND_WIN98_MEDIA) {
                    showImportWizard(a, host, destFile, base, kind, null);
                } else if (archiveMedia && kind == KIND_DOS_CD) {
                    host.importZipToLibraryAndDelete(destFile);
                } else if (kind == KIND_DOS_CD) {
                    Toast.makeText(a, base + " added to the CD library.", Toast.LENGTH_LONG).show();
                    host.rescan();
                } else {
                    host.runImport(destFile, dlg);
                }
            });
        }).start();
    }

    private static void showImportWizard(final Activity a, final GameLauncherActivity host,
                                         final File file, final String name, final int kind, final Uri treeUri) {
        String[] eras = {"Classic (1981-1987) - XT/AT, CGA/EGA", "Golden Era (1988-1992) - 386/486, VGA", "Late DOS (1993-1997) - Pentium, SVGA", "High Performance - Maxed out Pentium", "Custom / Advanced Machine"};
        final String[] eraPresets = {GameMeta.PRESET_80S, GameMeta.PRESET_90S_EARLY, GameMeta.PRESET_90S_LATE, GameMeta.PRESET_PENTIUM, GameMeta.PRESET_AUTO};
        new AlertDialog.Builder(a)
            .setTitle("Step 1: Select Game Era")
            .setItems(eras, (d, w) -> {
                GameMeta.setPreset(a, name, eraPresets[w]);
                showInputWizard(a, host, file, name, kind, treeUri);
            })
            .setCancelable(false)
            .show();
    }

    private static void showInputWizard(final Activity a, final GameLauncherActivity host,
                                        final File file, final String name, final int kind, final Uri treeUri) {
        String[] inputModes = {"Joystick Mode (Recommended for Gamepads)", "Keyboard Mode (Gamepad maps to arrow keys/ctrl/alt)"};
        new AlertDialog.Builder(a)
            .setTitle("Step 2: Input Mode")
            .setItems(inputModes, (d, w) -> {
                KeyMapStore.saveJoystickMode(a, name, w == 0);
                if (treeUri != null) importFolder(a, host, name, treeUri);
                else finalizeImport(a, host, file, name, kind);
            })
            .setCancelable(false)
            .show();
    }

    private static void finalizeImport(final Activity a, final GameLauncherActivity host,
                                       final File file, final String name, final int kind) {
        if (kind == KIND_DOS_CD_SETUP) {
            host.rescan();
            host.installCdToMsdos(file);
        } else if (kind == KIND_WIN98_MEDIA) {
            host.rescan();
            host.setupWin98FromMedia(file);
        } else {
            host.runImport(file, null);
        }
    }

    private static void importFolder(Activity a, GameLauncherActivity host, String name, Uri treeUri) {
        File destDir = new File(host.getGamesDir(), safeName(name));
        if (destDir.exists()) {
            Toast.makeText(a, "A game folder with that name already exists.", Toast.LENGTH_LONG).show();
            return;
        }
        destDir.mkdirs();
        final AlertDialog dlg = makeProgressDialog(a, "Copying folder " + name + "…");
        dlg.show();
        new Thread(() -> {
            boolean ok = copyDocumentTree(a, treeUri, destDir);
            a.runOnUiThread(() -> {
                dlg.dismiss();
                if (ok) {
                    Toast.makeText(a, name + " imported successfully.", Toast.LENGTH_SHORT).show();
                    host.rescan();
                } else {
                    Toast.makeText(a, "Failed to copy folder contents.", Toast.LENGTH_LONG).show();
                    deleteContents(destDir); destDir.delete();
                }
            });
        }).start();
    }

    private static boolean copyDocumentTree(Context context, Uri rootUri, File destDir) {
        DocumentFile root = DocumentFile.fromTreeUri(context, rootUri);
        if (root == null) return false;
        return copyDocumentFile(context, root, destDir);
    }

    private static boolean copyDocumentFile(Context context, DocumentFile src, File destDir) {
        if (src.isDirectory()) {
            if (!destDir.exists() && !destDir.mkdirs()) {
                Log.e("DosBoxX", "copyDocumentFile: failed to create dir " + destDir);
                return false;
            }
            for (DocumentFile file : src.listFiles()) {
                if (!copyDocumentFile(context, file, new File(destDir, file.getName()))) return false;
            }
            return true;
        } else {
            try (InputStream in = context.getContentResolver().openInputStream(src.getUri());
                 OutputStream out = new FileOutputStream(destDir)) {
                if (in == null) {
                    Log.e("DosBoxX", "copyDocumentFile: failed to open input stream for " + src.getUri());
                    return false;
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return true;
            } catch (Exception e) {
                Log.e("DosBoxX", "copyDocumentFile: error copying " + src.getUri() + " to " + destDir, e);
                return false;
            }
        }
    }

    public static void showEditWizard(final Activity a, final GameLauncherActivity host, final String name, final Runnable onDone) {
        String[] eras = {"Classic (1981-1987) - XT/AT, CGA/EGA", "Golden Era (1988-1992) - 386/486, VGA", "Late DOS (1993-1997) - Pentium, SVGA", "High Performance - Maxed out Pentium", "Custom / Advanced Machine"};
        final String[] eraPresets = {GameMeta.PRESET_80S, GameMeta.PRESET_90S_EARLY, GameMeta.PRESET_90S_LATE, GameMeta.PRESET_PENTIUM, GameMeta.PRESET_AUTO};
        new AlertDialog.Builder(a)
            .setTitle("Edit " + name + ": Era")
            .setItems(eras, (d, w) -> {
                GameMeta.setPreset(a, name, eraPresets[w]);
                showEditInputWizard(a, host, name, onDone);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void showEditInputWizard(final Activity a, final GameLauncherActivity host, final String name, final Runnable onDone) {
        String[] options = {
            "Joystick Mode (Recommended for Gamepads)",
            "Keyboard Mode (Gamepad maps to arrow keys/ctrl/alt)",
            "Map Individual Buttons..."
        };
        new AlertDialog.Builder(a)
            .setTitle("Edit " + name + ": Input")
            .setItems(options, (d, w) -> {
                if (w == 2) {
                    host.showControlMapper(name);
                } else {
                    KeyMapStore.saveJoystickMode(a, name, w == 0);
                    if (onDone != null) onDone.run();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static int kindForName(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".iso") || n.endsWith(".cue") || n.endsWith(".bin") || n.endsWith(".img") || n.endsWith(".lha") || n.endsWith(".whd") || n.endsWith(".hdf")) return KIND_DOS_CD;
        return KIND_DOS_GAME;
    }

    private static boolean isCdMediaName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".iso") || n.endsWith(".cue") || n.endsWith(".bin") || n.endsWith(".img") || n.endsWith(".zip") || n.endsWith(".lha") || n.endsWith(".whd") || n.endsWith(".hdf");
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "game" : out;
    }

    private static String queryDisplayName(Activity a, Uri uri) {
        try (Cursor c = a.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static File findInstaller(File folder) {
        List<File> bats = new ArrayList<>();
        List<File> exes = new ArrayList<>();
        scanLaunchers(folder, 3, bats, exes);
        for (File f : exes) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.startsWith("setup") || n.startsWith("install") || n.startsWith("dosinst")) return f;
        }
        for (File f : bats) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.startsWith("setup") || n.startsWith("install") || n.startsWith("dosinst")) return f;
        }
        return null;
    }

    private static void scanLaunchers(File dir, int depth, List<File> bats, List<File> exes) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (depth > 0) scanLaunchers(f, depth - 1, bats, exes);
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".bat")) bats.add(f);
                else if (n.endsWith(".exe") || n.endsWith(".com")) exes.add(f);
            }
        }
    }

    private static void deleteContents(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) deleteContents(f);
            f.delete();
        }
    }

    private static AlertDialog makeProgressDialog(Activity a, String title) {
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        TextView msg = new TextView(a);
        msg.setText(title);
        msg.setTextColor(0xFFE0E0E0);
        box.addView(msg);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = pad;
        box.addView(bar, blp);
        return new AlertDialog.Builder(a).setView(box).setCancelable(false).create();
    }
}
